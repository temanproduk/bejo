package com.bejo.core.service;

import com.bejo.core.domain.PasswordResetToken;
import com.bejo.core.domain.User;
import com.bejo.core.repository.PasswordResetTokenRepository;
import com.bejo.core.repository.UserRepository;
import com.bejo.notification.EmailSender;
import com.bejo.shared.exception.ServiceUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.StringUtils;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Password reset by an emailed 6-digit code, for the mobile app (which has no web page to open a link
 * in). Two public endpoints drive it: {@code POST /api/auth/forgot-password} emails a code to the
 * address on file, {@code POST /api/auth/reset-password} exchanges code + new password.
 *
 * <p><b>Why it is built this way</b>
 * <ul>
 *   <li><b>The code is never stored</b> — only {@code HMAC-SHA256(jwtSecret-derived pepper, userId:code)}.
 *       A 6-digit code is only a million values, so a plain hash would be reversible from a database
 *       leak; keyed with the server secret it is not.</li>
 *   <li><b>Guesses are bounded</b> — at most {@code max-attempts} per code, spent with an atomic
 *       conditional update ({@code spendAttempt}) so concurrent guesses cannot exceed it, and counted
 *       <i>before</i> the comparison so a wrong guess cannot be rolled back with its own exception.
 *       A code expires ({@code code-ttl-minutes}) and works once ({@code markUsed}).</li>
 *   <li><b>Requests are rate-limited per user</b> from the database (so it holds across instances): a
 *       resend cooldown and an hourly cap. Over the limit, the request is silently not sent.</li>
 *   <li><b>No account enumeration</b> — {@code forgot-password} answers identically whether or not the
 *       username exists, has an eligible email, or was rate-limited, and the response time is padded
 *       to {@code response-floor-ms} so "unknown user" is not visibly faster than "email sent".
 *       Every failure of {@code reset-password} gives the same message.</li>
 *   <li><b>No takeover through a stolen session</b> — changing the email needs no password, so a code
 *       is not sent to an address changed within {@code email-change-lockout-hours}; otherwise a stolen
 *       login could set its own email and reset the password to lock the owner out.</li>
 *   <li><b>A reset ends every session</b> — tokens carry a password-binding claim, so changing the
 *       password invalidates all previously issued tokens on every device. It also clears any login
 *       lockout, and marks the email verified (the user just proved they control it).</li>
 * </ul>
 *
 * <p>Email is sent synchronously inside the request, deliberately: on Cloud Run work after the response
 * is starved of CPU, which would delay or lose the one email the user is waiting for. Connection and read
 * timeouts bound how long that can take.
 */
// @Lazy(false): the @Scheduled purge must be registered at startup despite lazy initialization
@Service
@Lazy(false)
public class PasswordResetService {

    private static final Logger log = LoggerFactory.getLogger(PasswordResetService.class);

    /** The only message {@code reset-password} ever gives, whatever went wrong. */
    static final String INVALID_CODE = "Kode tidak valid atau sudah kedaluwarsa";

    static final String SUBJECT = "Kode reset kata sandi Bejo";

    private final UserRepository userRepository;
    private final PasswordResetTokenRepository tokenRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailSender emailSender;
    private final LoginThrottleService loginThrottle;
    private final TransactionTemplate tx;

    @Value("${app.jwt.secret}")
    private String pepperSource;
    @Value("${app.password-reset.code-ttl-minutes:15}")
    private int codeTtlMinutes;
    @Value("${app.password-reset.max-attempts:5}")
    private int maxAttempts;
    @Value("${app.password-reset.resend-cooldown-seconds:60}")
    private int resendCooldownSeconds;
    @Value("${app.password-reset.max-requests-per-hour:5}")
    private int maxRequestsPerHour;
    @Value("${app.password-reset.email-change-lockout-hours:24}")
    private int emailChangeLockoutHours;
    @Value("${app.password-reset.response-floor-ms:1500}")
    private long responseFloorMs;

    public PasswordResetService(UserRepository userRepository,
                                PasswordResetTokenRepository tokenRepository,
                                PasswordEncoder passwordEncoder,
                                EmailSender emailSender,
                                LoginThrottleService loginThrottle,
                                TransactionTemplate tx) {
        this.userRepository = userRepository;
        this.tokenRepository = tokenRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailSender = emailSender;
        this.loginThrottle = loginThrottle;
        this.tx = tx;
    }

    /**
     * Emails a reset code if — and only if — the user exists, has an eligible address and is under the
     * rate limits. The caller cannot tell which, by design. Throws only when email itself is not set up.
     */
    public void requestReset(String username) {
        if (!emailSender.isEnabled()) {
            throw new ServiceUnavailableException("Fitur reset password belum diaktifkan");
        }
        long started = System.nanoTime();
        try {
            issueCodeIfEligible(username.trim());
        } catch (RuntimeException e) {
            // A database or mail-server problem must look exactly like success to the caller
            log.error("[PASSWORD-RESET] Could not issue a code: {}", e.getMessage());
        }
        sleepUntilFloor(started);
    }

    private void issueCodeIfEligible(String username) {
        Optional<User> found = userRepository.findByUsernameAndDeletedAtIsNull(username);
        if (found.isEmpty()) {
            log.info("[PASSWORD-RESET] Request ignored: no such active user");
            return;
        }
        User user = found.get();
        LocalDateTime now = LocalDateTime.now();

        if (!StringUtils.hasText(user.getEmail())) {
            log.warn("[PASSWORD-RESET] Request ignored for username={}: no email on file", username);
            return;
        }
        if (user.getEmailChangedAt() != null
                && user.getEmailChangedAt().isAfter(now.minusHours(emailChangeLockoutHours))) {
            log.warn("[PASSWORD-RESET] Request ignored for username={}: email changed within {}h",
                    username, emailChangeLockoutHours);
            return;
        }
        Optional<PasswordResetToken> last = tokenRepository.findFirstByUserIdOrderByCreatedAtDesc(user.getId());
        if (last.isPresent() && last.get().getCreatedAt().isAfter(now.minusSeconds(resendCooldownSeconds))) {
            log.info("[PASSWORD-RESET] Request ignored for username={}: resend cooldown", username);
            return;
        }
        if (tokenRepository.countByUserIdAndCreatedAtAfter(user.getId(), now.minusHours(1)) >= maxRequestsPerHour) {
            log.warn("[PASSWORD-RESET] Request ignored for username={}: hourly limit reached", username);
            return;
        }

        String code = OneTimeCode.generate();
        tx.executeWithoutResult(status -> {
            tokenRepository.invalidateOpen(user.getId(), now);   // a new code replaces any open one
            PasswordResetToken token = new PasswordResetToken();
            token.setUserId(user.getId());
            token.setCodeHash(hash(user.getId(), code));
            token.setCreatedAt(now);
            token.setExpiresAt(now.plusMinutes(codeTtlMinutes));
            tokenRepository.save(token);
        });
        emailSender.send(user.getEmail(), SUBJECT, body(user, code));
        log.info("[PASSWORD-RESET] Code sent for username={}", username);
    }

    /**
     * Sets a new password if the code is right. Every failure — unknown user, no open code, wrong code,
     * expired, used, out of attempts — throws the same {@link #INVALID_CODE} message.
     */
    public void resetPassword(String username, String code, String newPassword) {
        LocalDateTime now = LocalDateTime.now();
        User user = userRepository.findByUsernameAndDeletedAtIsNull(username.trim())
                .orElseThrow(() -> new IllegalArgumentException(INVALID_CODE));
        PasswordResetToken token = tokenRepository
                .findFirstByUserIdAndUsedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(user.getId(), now)
                .orElseThrow(() -> new IllegalArgumentException(INVALID_CODE));

        // Spend the guess first and outside any transaction that a failure would roll back
        if (tokenRepository.spendAttempt(token.getId(), now, maxAttempts) != 1) {
            log.warn("[PASSWORD-RESET] Code locked for username={}: out of attempts", user.getUsername());
            throw new IllegalArgumentException(INVALID_CODE);
        }
        if (!MessageDigest.isEqual(hash(user.getId(), code).getBytes(StandardCharsets.UTF_8),
                token.getCodeHash().getBytes(StandardCharsets.UTF_8))) {
            log.warn("[PASSWORD-RESET] Wrong code for username={}", user.getUsername());
            throw new IllegalArgumentException(INVALID_CODE);
        }

        tx.executeWithoutResult(status -> {
            if (tokenRepository.markUsed(token.getId(), now) != 1) {
                throw new IllegalArgumentException(INVALID_CODE);   // lost a race with another submit
            }
            User fresh = userRepository.findById(user.getId())
                    .orElseThrow(() -> new IllegalArgumentException(INVALID_CODE));
            fresh.setPasswordHash(passwordEncoder.encode(newPassword));
            fresh.setEmailVerified(true);
            userRepository.save(fresh);
            tokenRepository.invalidateOpen(user.getId(), now);
        });
        loginThrottle.recordSuccess(user.getUsername());   // a reset also lifts a login lockout
        log.info("[PASSWORD-RESET] Password reset for username={}", user.getUsername());
    }

    /** Spent and long-expired codes are only history; drop them a day after they expire. */
    @Scheduled(fixedDelay = 3_600_000)
    public void purgeExpired() {
        int removed = tokenRepository.deleteExpiredBefore(LocalDateTime.now().minusDays(1));
        if (removed > 0) log.debug("[PASSWORD-RESET] Purged {} old code(s)", removed);
    }

    // ---- helpers ----

    private String hash(Long userId, String code) {
        return OneTimeCode.hash("password-reset", pepperSource, String.valueOf(userId), code);
    }

    private String body(User user, String code) {
        return "Halo " + user.getName() + ",\n\n"
                + "Kami menerima permintaan untuk mereset kata sandi akun Bejo Anda (username: "
                + user.getUsername() + ").\n\n"
                + "Kode reset Anda: " + code + "\n\n"
                + "Kode ini berlaku selama " + codeTtlMinutes + " menit dan hanya dapat dipakai sekali. "
                + "Jangan bagikan kode ini kepada siapa pun, termasuk pihak yang mengaku dari Bejo.\n\n"
                + "Jika Anda tidak meminta reset kata sandi, abaikan email ini. Kata sandi Anda tidak berubah.\n";
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
