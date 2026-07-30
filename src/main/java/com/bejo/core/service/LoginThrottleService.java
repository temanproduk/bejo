package com.bejo.core.service;

import com.bejo.shared.exception.TooManyRequestsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Brute-force brake on the login endpoint, keyed on <b>username</b>.
 *
 * <p>Deliberately gentle: it only trips after {@code app.security.login.max-failures}
 * <em>consecutive</em> failures for one account (default 10 — well above a human fat-fingering a
 * password), then makes that account wait {@code app.security.login.lockout-minutes} (default 15).
 * A single success clears the counter, so a legitimate user who eventually remembers their
 * password is never left locked out. The point is to make exhaustive password guessing
 * impractical, not to punish typos — the shared {@code X-API-TOKEN} ships inside the mobile app
 * and so can't be treated as a secret that gates this endpoint.
 *
 * <p>State is <b>in-memory per instance</b> (same trade-off as the push queue): with the service
 * capped at 2 Cloud Run instances an attacker gets at most 2× the budget, and a restart forgives
 * outstanding counters. That is a deliberate simplicity choice — moving to Redis later means
 * swapping this bean's internals only, as callers just see {@link #assertNotLocked}/
 * {@link #recordFailure}/{@link #recordSuccess}.
 */
// @Lazy(false): created at startup so the @Scheduled purge is registered even with
// spring.main.lazy-initialization=true.
@Service
@Lazy(false)
public class LoginThrottleService {

    private static final Logger log = LoggerFactory.getLogger(LoginThrottleService.class);

    // Safety valve: stop an attacker spraying random usernames from growing the map without
    // bound. Well above any real business's user count; on overflow the map is simply reset.
    private static final int MAX_TRACKED_ACCOUNTS = 50_000;

    @Value("${app.security.login.max-failures:10}")
    private int maxFailures;

    @Value("${app.security.login.lockout-minutes:15}")
    private long lockoutMinutes;

    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    private static final class Attempts {
        private final AtomicInteger failures = new AtomicInteger();
        private volatile LocalDateTime lockedUntil;
        private volatile LocalDateTime lastFailureAt = LocalDateTime.now();
    }

    /** Rejects with 429 while the account is locked out. Call before checking the password. */
    public void assertNotLocked(String username) {
        Attempts a = attempts.get(key(username));
        if (a == null || a.lockedUntil == null) {
            return;
        }
        LocalDateTime now = LocalDateTime.now();
        if (a.lockedUntil.isAfter(now)) {
            long waitMinutes = Math.max(1, Duration.between(now, a.lockedUntil).toMinutes() + 1);
            throw new TooManyRequestsException(
                    "Terlalu banyak percobaan masuk. Coba lagi dalam " + waitMinutes + " menit.");
        }
        // Lockout elapsed — start the account fresh rather than leaving it one failure from re-locking.
        attempts.remove(key(username));
    }

    /** Records a wrong password (or unknown username); locks the account once the budget is spent. */
    public void recordFailure(String username) {
        if (attempts.size() >= MAX_TRACKED_ACCOUNTS) {
            log.warn("[LOGIN-THROTTLE] Tracking table full ({} accounts) — resetting", attempts.size());
            attempts.clear();
        }
        Attempts a = attempts.computeIfAbsent(key(username), k -> new Attempts());
        a.lastFailureAt = LocalDateTime.now();
        if (a.failures.incrementAndGet() >= maxFailures) {
            a.lockedUntil = LocalDateTime.now().plusMinutes(lockoutMinutes);
            a.failures.set(0);
            log.warn("[LOGIN-THROTTLE] username={} locked out until {} after {} failed attempts",
                    username, a.lockedUntil, maxFailures);
        }
    }

    /** A successful login clears the account's failure history. */
    public void recordSuccess(String username) {
        attempts.remove(key(username));
    }

    // Drop entries that are neither locked nor recently active, so the map tracks only
    // accounts actually under attack.
    @Scheduled(fixedDelay = 3_600_000)
    public void purgeStale() {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(1);
        attempts.entrySet().removeIf(e -> {
            Attempts a = e.getValue();
            boolean stillLocked = a.lockedUntil != null && a.lockedUntil.isAfter(LocalDateTime.now());
            return !stillLocked && a.lastFailureAt.isBefore(cutoff);
        });
        log.debug("[LOGIN-THROTTLE] Purged stale entries, {} accounts tracked", attempts.size());
    }

    // Usernames are case-sensitive for lookup elsewhere, but normalize here so case variations
    // can't be used to multiply the attempt budget for one account.
    private String key(String username) {
        return username == null ? "" : username.toLowerCase();
    }
}
