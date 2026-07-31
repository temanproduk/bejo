package com.bejo.core.notification;

import com.bejo.core.domain.Session;
import com.bejo.notification.PushRecipient;
import com.bejo.notification.PushRecipientDirectory;
import com.bejo.core.repository.SessionRepository;
import com.bejo.core.repository.UserRepository;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Core's adapter for the notification module's {@link PushRecipientDirectory} port: turns a push
 * addressing request (a user / device / business / set of users) into concrete {@link PushRecipient}s
 * by reading {@code sessions}. This is the session-reading fan-out that used to live inside the
 * push service — moved into core so the notification module owns no session knowledge (Option B).
 *
 * <p>The {@code recipientRef} carried on each recipient is the session's id, so when the notification
 * module reports a dead token back via {@link SessionStaleTokenHandler} we know exactly which row to
 * clear. Token-less devices are dropped here — a device with no FCM token can't be pushed to.
 */
@Component
public class SessionPushRecipientDirectory implements PushRecipientDirectory {

    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;

    public SessionPushRecipientDirectory(SessionRepository sessionRepository, UserRepository userRepository) {
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
    }

    @Override
    public List<PushRecipient> forUser(String username) {
        return toRecipients(sessionRepository.findByUsername(username));
    }

    @Override
    public List<PushRecipient> forDevice(String username, String deviceId) {
        return sessionRepository.findByUsernameAndDeviceId(username, deviceId)
                .map(List::of).map(this::toRecipients).orElseGet(List::of);
    }

    @Override
    public List<PushRecipient> forBusiness(Long businessId) {
        List<String> usernames = userRepository.findUsernamesByBusinessId(businessId);
        if (usernames.isEmpty()) return List.of();
        return toRecipients(sessionRepository.findByUsernameIn(usernames));
    }

    @Override
    public List<PushRecipient> forUsernames(Collection<String> usernames) {
        if (usernames.isEmpty()) return List.of();
        return toRecipients(sessionRepository.findByUsernameIn(usernames));
    }

    // Keep only devices with a usable token; carry the session id as the opaque recipient ref.
    private List<PushRecipient> toRecipients(List<Session> sessions) {
        List<PushRecipient> recipients = new ArrayList<>();
        for (Session s : sessions) {
            String token = s.getPushToken();
            if (token == null || token.isBlank()) continue;
            recipients.add(new PushRecipient(token, s.getId(), s.getDeviceId()));
        }
        return recipients;
    }
}
