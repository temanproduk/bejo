package com.bejo.admin.service;

import com.bejo.admin.domain.AdminOtpCode;
import com.bejo.admin.domain.AdminSession;
import com.bejo.admin.dto.AdminLoginResponseDto;
import com.bejo.admin.repository.AdminOtpCodeRepository;
import com.bejo.admin.repository.AdminSessionRepository;
import com.bejo.notification.EmailSender;
import com.bejo.shared.exception.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Sign-in for the admin dashboard: an 8-digit one-time code emailed to an address on the
 * {@code app.admin.emails} allow-list ({@code ADMIN_EMAILS}). Admins have no password and no row in
 * {@code core.users}; the allow-list is the whole definition of who is an admin, so an empty list
 * means nobody can sign in.
 *
 * <p>The code handling mirrors {@code PasswordResetService}: only a keyed hash of the code is stored,
 * guesses are bounded by an atomic conditional update and counted before the comparison, a code
 * expires and works once, requests are rate-limited per address from the database, and
 * {@code otp/request} answers identically (and no faster) for an address that is not an admin.
 *
 * <p>A successful sign-in starts a session: a random bearer token of which only the SHA-256 is stored.
 * It is checked against the database on every request, so signing out, expiry, or removing the address
 * from the allow-list ends it immediately. Sessions are opaque rather than JWTs so that they share
 * nothing with the app's user tokens — neither kind can ever be accepted in place of the other.
 */
// @Lazy(false): the @Scheduled purge must be registered at startup despite lazy initialization
@Service
@Lazy(false)
public class AdminAuthService {

    private static final Logger log = LoggerFactory.getLogger(AdminAuthService.class);

    /** The only message {@code otp/verify} ever gives, whatever went wrong. */
    static final String INVALID_CODE = "Kode tidak valid atau sudah kedaluwarsa";

    static final String SUBJECT = "Kode masuk Bejo Admin";
    static final int CODE_DIGITS = 8;

    private final AdminOtpCodeRepository codeRepository;
    private final AdminSessionRepository sessionRepository;
    private final EmailSender emailSender;
    private final TransactionTemplate tx;
    private final SecureRandom random = new SecureRandom();
    private final Set<String> adminEmails;

    @Value("${app.jwt.secret}")
    private String pepperSource;
    @Value("${app.admin.otp.code-ttl-minutes:10}")
    private int codeTtlMinutes;
    @Value("${app.admin.otp.max-attempts:5}")
    private int maxAttempts;
    @Value("${app.admin.otp.resend-cooldown-seconds:60}")
    private int resendCooldownSeconds;
    @Value("${app.admin.otp.max-requests-per-hour:5}")
    private int maxRequestsPerHour;
    @Value("${app.admin.otp.response-floor-ms:1500}")
    private long responseFloorMs;
    @Value("${app.admin.session-ttl-hours:12}")
    private int sessionTtlHours;

    public AdminAuthService(AdminOtpCodeRepository codeRepository,
                            AdminSessionRepository sessionRepository,
                            EmailSender emailSender,
                            TransactionTemplate tx,
                            @Value("${app.admin.emails:}") String adminEmails) {
        this.codeRepository = codeRepository;
        this.sessionRepository = sessionRepository;
        this.emailSender = emailSender;
        this.tx = tx;
        this.adminEmails = Arrays.stream(adminEmails.split(","))
                .map(AdminAuthService::normalize)
                .filter(StringUtils::hasText)
                .collect(Collectors.toUnmodifiableSet());
        if (this.adminEmails.isEmpty()) {
            log.warn("[ADMIN] ADMIN_EMAILS is empty: nobody can sign in to the admin dashboard");
        }
    }

    /**
     * Emails a sign-in code if — and only if — the address is an admin and under the rate limits. The
     * caller cannot tell which, by design. Throws only when email itself is not set up.
     */
    public void requestCode(String rawEmail) {
        if (!emailSender.isEnabled()) {
            throw new ServiceUnavailableException("Email belum dikonfigurasi, dasbor admin tidak dapat dipakai");
        }
        long started = System.nanoTime();
        try {
            issueCodeIfEligible(normalize(rawEmail));
        } catch (RuntimeException e) {
            // A database or mail-server problem must look exactly like success to the caller
            log.error("[ADMIN] Could not issue a sign-in code: {}", e.getMessage());
        }
        sleepUntilFloor(started);
    }

    private void issueCodeIfEligible(String email) {
        if (!adminEmails.contains(email)) {
            log.warn("[ADMIN] Sign-in code requested for a non-admin address");
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        Optional<AdminOtpCode> last = codeRepository.findFirstByEmailOrderByCreatedAtDesc(email);
        if (last.isPresent() && last.get().getCreatedAt().isAfter(now.minusSeconds(resendCooldownSeconds))) {
            log.info("[ADMIN] Code request ignored for {}: resend cooldown", email);
            return;
        }
        if (codeRepository.countByEmailAndCreatedAtAfter(email, now.minusHours(1)) >= maxRequestsPerHour) {
            log.warn("[ADMIN] Code request ignored for {}: hourly limit reached", email);
            return;
        }

        String code = String.format("%0" + CODE_DIGITS + "d", random.nextInt(100_000_000));
        tx.executeWithoutResult(status -> {
            codeRepository.invalidateOpen(email, now);   // a new code replaces any open one
            AdminOtpCode row = new AdminOtpCode();
            row.setEmail(email);
            row.setCodeHash(hashCode(email, code));
            row.setCreatedAt(now);
            row.setExpiresAt(now.plusMinutes(codeTtlMinutes));
            codeRepository.save(row);
        });
        emailSender.send(email, SUBJECT, body(code));
        log.info("[ADMIN] Sign-in code sent to {}", email);
    }

    /**
     * Exchanges a correct code for a session. Every failure — not an admin, no open code, wrong code,
     * expired, used, out of attempts — throws the same {@link #INVALID_CODE} message.
     */
    public AdminLoginResponseDto verifyCode(String rawEmail, String code) {
        String email = normalize(rawEmail);
        LocalDateTime now = LocalDateTime.now();
        if (!adminEmails.contains(email)) {
            throw new IllegalArgumentException(INVALID_CODE);
        }
        AdminOtpCode open = codeRepository
                .findFirstByEmailAndUsedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(email, now)
                .orElseThrow(() -> new IllegalArgumentException(INVALID_CODE));

        // Spend the guess first and outside any transaction that a failure would roll back
        if (codeRepository.spendAttempt(open.getId(), now, maxAttempts) != 1) {
            log.warn("[ADMIN] Code locked for {}: out of attempts", email);
            throw new IllegalArgumentException(INVALID_CODE);
        }
        if (!MessageDigest.isEqual(hashCode(email, code).getBytes(StandardCharsets.UTF_8),
                open.getCodeHash().getBytes(StandardCharsets.UTF_8))) {
            log.warn("[ADMIN] Wrong sign-in code for {}", email);
            throw new IllegalArgumentException(INVALID_CODE);
        }

        byte[] tokenBytes = new byte[32];
        random.nextBytes(tokenBytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
        tx.executeWithoutResult(status -> {
            if (codeRepository.markUsed(open.getId(), now) != 1) {
                throw new IllegalArgumentException(INVALID_CODE);   // lost a race with another submit
            }
            AdminSession session = new AdminSession();
            session.setTokenHash(hashToken(token));
            session.setEmail(email);
            session.setCreatedAt(now);
            session.setExpiresAt(now.plusHours(sessionTtlHours));
            sessionRepository.save(session);
        });
        log.info("[ADMIN] Signed in: {}", email);
        return new AdminLoginResponseDto(token, "Bearer", sessionTtlHours * 3600L, email);
    }

    /** The admin a bearer token belongs to, or empty if it does not authenticate. */
    public Optional<String> authenticate(String token) {
        if (!StringUtils.hasText(token)) {
            return Optional.empty();
        }
        return sessionRepository
                .findByTokenHashAndRevokedAtIsNullAndExpiresAtAfter(hashToken(token), LocalDateTime.now())
                .map(AdminSession::getEmail)
                .filter(adminEmails::contains);   // taken off the allow-list = signed out
    }

    public void signOut(String token) {
        sessionRepository.revoke(hashToken(token), LocalDateTime.now());
    }

    /** Spent codes and ended sessions are only history; drop them a day after they expire. */
    @Scheduled(fixedDelay = 3_600_000)
    public void purgeExpired() {
        LocalDateTime before = LocalDateTime.now().minusDays(1);
        int removed = codeRepository.deleteExpiredBefore(before) + sessionRepository.deleteExpiredBefore(before);
        if (removed > 0) log.debug("[ADMIN] Purged {} old code(s)/session(s)", removed);
    }

    // ---- helpers ----

    private static String normalize(String email) {
        return email == null ? "" : email.trim().toLowerCase(Locale.ROOT);
    }

    // Keyed: an 8-digit code has too few values for a plain hash to survive a database leak
    private String hashCode(String email, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(("admin-otp:" + pepperSource).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((email + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot hash sign-in code", e);
        }
    }

    // Unkeyed is enough here: the token is 256 random bits, so its hash cannot be reversed by guessing
    private static String hashToken(String token) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot hash session token", e);
        }
    }

    private String body(String code) {
        return "Halo,\n\n"
                + "Kode masuk Bejo Admin Anda: " + code + "\n\n"
                + "Kode ini berlaku selama " + codeTtlMinutes + " menit dan hanya dapat dipakai sekali. "
                + "Jangan bagikan kode ini kepada siapa pun.\n\n"
                + "Jika Anda tidak mencoba masuk ke dasbor admin Bejo, abaikan email ini.\n";
    }

    /** Pads the response so that an ignored request takes about as long as a sent email. */
    private void sleepUntilFloor(long startedNanos) {
        long remainingMs = responseFloorMs - (System.nanoTime() - startedNanos) / 1_000_000;
        if (remainingMs > 0) {
            try {
                Thread.sleep(remainingMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
