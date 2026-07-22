package com.bejo.push;

import com.bejo.repository.SessionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Clears dead FCM tokens from a push worker thread. A separate bean (not a method on
 * {@link PushNotificationService}) so the {@code @Transactional} write goes through the Spring
 * proxy — the worker has no ambient transaction, and self-invocation would bypass it.
 */
@Component
public class PushTokenMaintenance {

    private final SessionRepository sessionRepository;

    public PushTokenMaintenance(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    /** Clear the session's token, but only if it's still the stale one FCM rejected (race-safe). */
    @Transactional
    public void clearStaleToken(Long sessionId, String staleToken) {
        sessionRepository.clearPushTokenIfMatches(sessionId, staleToken);
    }
}
