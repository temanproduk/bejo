package com.bejo.core.notification;

import com.bejo.notification.StaleTokenHandler;
import com.bejo.core.repository.SessionRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Core's adapter for the notification module's {@link StaleTokenHandler} port: when FCM rejects a
 * token, clears it from the owning {@code bejo_sessions} row. The {@code recipientRef} the
 * notification module hands back is the session id it originally supplied via {@link
 * com.bejo.notification.PushRecipient} (see {@link SessionPushRecipientDirectory}).
 *
 * <p>A dedicated bean so the {@code @Transactional} write goes through the Spring proxy — a push
 * worker thread has no ambient transaction, and self-invocation would bypass it. The clear is
 * race-safe: it only nulls the token if it's still the stale one FCM rejected, so a concurrent
 * re-login isn't clobbered.
 */
@Component
public class SessionStaleTokenHandler implements StaleTokenHandler {

    private final SessionRepository sessionRepository;

    public SessionStaleTokenHandler(SessionRepository sessionRepository) {
        this.sessionRepository = sessionRepository;
    }

    @Override
    @Transactional
    public void onTokenRejected(Long recipientRef, String staleToken) {
        sessionRepository.clearPushTokenIfMatches(recipientRef, staleToken);
    }
}
