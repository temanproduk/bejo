package com.bejo.core.service;

import com.bejo.core.domain.EmailVerificationToken;
import com.bejo.core.domain.User;
import com.bejo.core.repository.EmailVerificationTokenRepository;
import com.bejo.core.repository.UserRepository;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Proves a user controls the email address on their account, with an emailed 6-digit code. Login is
 * refused until it is done (see {@code AuthService}), so a new self-service registration cannot be used
 * with someone else's address, and the address password-reset codes go to is known to be reachable.
 *
 * <p>Same design as {@link PasswordResetService}, for the same reasons: the code is stored only as a keyed
 * hash, guesses are bounded and spent atomically <i>before</i> comparing, a code expires and works once,
 * and requests are rate-limited per user from the database. Differences:
 * <ul>
 *   <li>The hash covers the <b>address</b> as well as the user, so a code sent to an old address cannot
 *       verify a newer one (a user may change their email while a code is outstanding).</li>
 *   <li>{@link #resend} answers identically for any username (no account enumeration) and pads its
 *       response time, like {@code forgot-password}. {@link #verify} gives one message for every failure.</li>
 *   <li>The email goes out synchronously inside the request, for the same Cloud Run reason as the reset
 *       email: work after the response is starved of CPU.</li>
 * </ul>
 *
 * <p>Who needs it: self-service registrations (the owner, a {@code SUPER_ADMIN}), and an owner who changes
 * their own email. Sub-accounts ({@code MEMBER}) never do — the owner who adds them vouches for them — and the
 * seed admin is created verified.
 */
// @Lazy(false): the @Scheduled purge must be registered at startup despite lazy initialization
@Service
@Lazy(false)
public class EmailVerificationService {

    private static final Logger log = LoggerFactory.getLogger(EmailVerificationService.class);

    /** The only message {@link #verify} ever gives, whatever went wrong. */
    static final String INVALID_CODE = "Kode tidak valid atau sudah kedaluwarsa";

    static final String SUBJECT = "Kode verifikasi email Bejo";

    private final UserRepository userRepository;
    private final EmailVerificationTokenRepository tokenRepository;
    private final EmailSender emailSender;
    private final TransactionTemplate tx;

    @Value("${app.jwt.secret}")
    private String pepperSource;
    @Value("${app.email-verification.code-ttl-minutes:15}")
    private int codeTtlMinutes;
    @Value("${app.email-verification.max-attempts:5}")
    private int maxAttempts;
    @Value("${app.email-verification.resend-cooldown-seconds:60}")
    private int resendCooldownSeconds;
    @Value("${app.email-verification.max-requests-per-hour:5}")
    private int maxRequestsPerHour;
    @Value("${app.email-verification.response-floor-ms:1500}")
    private long responseFloorMs;

    public EmailVerificationService(UserRepository userRepository,
                                    EmailVerificationTokenRepository tokenRepository,
                                    EmailSender emailSender,
                                    TransactionTemplate tx) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.emailSender = emailSender;
        this.tx = tx;
    }

    /** Whether codes can be emailed at all. Without it a new account could never be verified. */
    public boolean isEnabled() {
        return emailSender.isEnabled();
    }

    /** Refuses (503) when email is not set up, so nothing is created that could never be verified. */
    public void requireEnabled() {
        if (!emailSender.isEnabled()) {
            throw new ServiceUnavailableException("Verifikasi email belum tersedia. Coba lagi nanti.");
        }
    }

    /** {@link #sendCode(String, boolean)} for a new account: the resend cooldown applies. */
    public boolean sendCode(String username) {
        return sendCode(username, false);
    }

    /**
     * Emails a fresh code to the user's current address if they are unverified and under the rate limits.
     * For callers that just created the account or changed its address. Never throws: a mail problem must
     * not undo the registration — the user can ask for another code.
     *
     * @param newAddress true right after the address changed: the new address gets its code at once instead
     *                   of waiting out the cooldown left by the previous address's code. The hourly cap still
     *                   applies, so changing the address over and over cannot be used to flood a mailbox.
     * @return whether a code was sent
     */
    public boolean sendCode(String username, boolean newAddress) {
        if (!emailSender.isEnabled()) return false;
        try {
            Optional<User> user = userRepository.findByUsernameAndDeletedAtIsNull(username);
            return user.isPresent() && issueIfEligible(user.get(), newAddress);
        } catch (RuntimeException e) {
            log.error("[EMAIL-VERIFY] Could not send a code to username={}: {}", username, e.getMessage());
            return false;
        }
    }

    /**
     * The public "send me another code" request. Emails a code if — and only if — the user exists, is not
     * verified yet and is under the rate limits; the caller cannot tell which, by design. Throws only when
     * email itself is not set up.
     */
    public void resend(String username) {
        requireEnabled();
        long started = System.nanoTime();
        try {
            Optional<User> user = userRepository.findByUsernameAndDeletedAtIsNull(username.trim());
            if (user.isEmpty()) {
                log.info("[EMAIL-VERIFY] Resend ignored: no such active user");
            } else {
                issueIfEligible(user.get(), false);
            }
        } catch (RuntimeException e) {
            // A database or mail-server problem must look exactly like success to the caller
            log.error("[EMAIL-VERIFY] Could not resend a code: {}", e.getMessage());
        }
        sleepUntilFloor(started);
    }

    private boolean issueIfEligible(User user, boolean skipCooldown) {
        LocalDateTime now = LocalDateTime.now();
        if (user.isEmailVerified()) {
            log.info("[EMAIL-VERIFY] Not sending to username={}: already verified", user.getUsername());
            return false;
        }
        if (!user.isSuperAdmin()) {
            log.info("[EMAIL-VERIFY] Not sending to username={}: sub-accounts never need to verify", user.getUsername());
            return false;
        }
        if (!StringUtils.hasText(user.getEmail())) {
            log.warn("[EMAIL-VERIFY] Not sending to username={}: no email on file", user.getUsername());
            return false;
        }
        Optional<EmailVerificationToken> last = tokenRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId());
        if (!skipCooldown && last.isPresent()
                && last.get().getCreatedAt().isAfter(now.minusSeconds(resendCooldownSeconds))) {
            log.info("[EMAIL-VERIFY] Not sending to username={}: resend cooldown", user.getUsername());
            return false;
        }
        if (tokenRepository.countByUserIdAndCreatedAtAfter(user.getId(), now.minusHours(1)) >= maxRequestsPerHour) {
            log.warn("[EMAIL-VERIFY] Not sending to username={}: hourly limit reached", user.getUsername());
            return false;
        }

        String code = OneTimeCode.generate();
        EmailVerificationToken token = new EmailVerificationToken();
        token.setUserId(user.getId());
        token.setCodeHash(hash(user, code));
        token.setCreatedAt(now);
        token.setExpiresAt(now.plusMinutes(codeTtlMinutes));
        tx.executeWithoutResult(status -> {
            tokenRepository.invalidateOpen(user.getId(), now);   // a new code replaces any open one
            tokenRepository.save(token);
        });
        try {
            emailSender.send(user.getEmail(), SUBJECT, body(user, code));
        } catch (RuntimeException e) {
            // Nothing reached the user, so the code must not count against the cooldown or the hourly cap:
            // otherwise a mail outage at registration would also lock "resend" for a minute.
            tokenRepository.delete(token);
            throw e;
        }
        log.info("[EMAIL-VERIFY] Code sent for username={}", user.getUsername());
        return true;
    }

    /**
     * Marks the email verified if the code is right. Every failure — unknown user, no open code, wrong
     * code, expired, used, out of attempts — throws the same {@link #INVALID_CODE} message.
     */
    public void verify(String username, String code) {
        LocalDateTime now = LocalDateTime.now();
        User user = userRepository.findByUsernameAndDeletedAtIsNull(username.trim())
                .orElseThrow(() -> new IllegalArgumentException(INVALID_CODE));
        EmailVerificationToken token = tokenRepository
                .findFirstByUserIdAndUsedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(user.getId(), now)
                .orElseThrow(() -> new IllegalArgumentException(INVALID_CODE));

        // Spend the guess first and outside any transaction that a failure would roll back
        if (tokenRepository.spendAttempt(token.getId(), now, maxAttempts) != 1) {
            log.warn("[EMAIL-VERIFY] Code locked for username={}: out of attempts", user.getUsername());
            throw new IllegalArgumentException(INVALID_CODE);
        }
        if (!MessageDigest.isEqual(hash(user, code).getBytes(StandardCharsets.UTF_8),
                token.getCodeHash().getBytes(StandardCharsets.UTF_8))) {
            log.warn("[EMAIL-VERIFY] Wrong code for username={}", user.getUsername());
            throw new IllegalArgumentException(INVALID_CODE);
        }

        tx.executeWithoutResult(status -> {
            if (tokenRepository.markUsed(token.getId(), now) != 1) {
                throw new IllegalArgumentException(INVALID_CODE);   // lost a race with another submit
            }
            User fresh = userRepository.findById(user.getId())
                    .orElseThrow(() -> new IllegalArgumentException(INVALID_CODE));
            fresh.setEmailVerified(true);
            userRepository.save(fresh);
            tokenRepository.invalidateOpen(user.getId(), now);
        });
        log.info("[EMAIL-VERIFY] Email verified for username={}", user.getUsername());
    }

    /**
     * Retires the user's outstanding codes. Called when the address changes: a code that went to the old
     * address must stop being usable (it would not match the new one anyway, but it should not linger).
     */
    public void invalidateOpenCodes(Long userId) {
        tokenRepository.invalidateOpen(userId, LocalDateTime.now());
    }

    /** "budi@example.com" → "b***@example.com": enough to recognise the address, not to harvest it. */
    public static String maskEmail(String email) {
        if (!StringUtils.hasText(email)) return null;
        int at = email.indexOf('@');
        if (at <= 0) return "***";
        return email.charAt(0) + "***" + email.substring(at);
    }

    /** Spent and long-expired codes are only history; drop them a day after they expire. */
    @Scheduled(fixedDelay = 3_600_000)
    public void purgeExpired() {
        int removed = tokenRepository.deleteExpiredBefore(LocalDateTime.now().minusDays(1));
        if (removed > 0) log.debug("[EMAIL-VERIFY] Purged {} old code(s)", removed);
    }

    // ---- helpers ----

    /** Keyed on the user AND the address, so a code sent to one address never confirms another. */
    private String hash(User user, String code) {
        String address = user.getEmail() == null ? "" : user.getEmail().trim().toLowerCase();
        return OneTimeCode.hash("email-verification", pepperSource, user.getId() + ":" + address, code);
    }

    private String body(User user, String code) {
        return "Halo " + user.getName() + ",\n\n"
                + "Terima kasih telah mendaftar di Bejo. Untuk mengaktifkan akun Anda (username: "
                + user.getUsername() + "), masukkan kode berikut di aplikasi.\n\n"
                + "Kode verifikasi Anda: " + code + "\n\n"
                + "Kode ini berlaku selama " + codeTtlMinutes + " menit dan hanya dapat dipakai sekali. "
                + "Jangan bagikan kode ini kepada siapa pun, termasuk pihak yang mengaku dari Bejo.\n\n"
                + "Jika Anda tidak merasa mendaftar atau mengubah email di Bejo, abaikan email ini.\n";
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
