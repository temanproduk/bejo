package com.bejo.service;

import com.bejo.entity.Session;
import com.bejo.repository.SessionRepository;
import com.bejo.repository.UserRepository;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

/**
 * Low-level FCM sender shared by the callback flow and the test-notification API.
 * Sends data-only messages; does not touch the database.
 */
@Service
public class PushNotificationService {

    private static final Logger log = LoggerFactory.getLogger(PushNotificationService.class);

    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final Counter successCounter;
    private final Counter failureCounter;

    public PushNotificationService(SessionRepository sessionRepository,
                                   UserRepository userRepository,
                                   MeterRegistry meterRegistry) {
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
        this.successCounter = Counter.builder("notifications.sent").tag("result", "success").register(meterRegistry);
        this.failureCounter = Counter.builder("notifications.sent").tag("result", "failure").register(meterRegistry);
    }

    public boolean isConfigured() {
        return !FirebaseApp.getApps().isEmpty();
    }

    /**
     * True when FCM's error means the token is dead and should be removed — the device
     * unregistered (app uninstalled / token rotated → "Requested entity was not found")
     * or the token belongs to a different Firebase sender. Retrying it is pointless.
     */
    public static boolean isTokenUnregistered(MessagingErrorCode code) {
        return code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.SENDER_ID_MISMATCH;
    }

    /** Send to a single device token. Propagates FirebaseMessagingException to the caller. */
    public String sendToToken(String pushToken, Map<String, String> data) throws FirebaseMessagingException {
        Message message = Message.builder().putAllData(data).setToken(pushToken).build();
        try {
            String messageId = FirebaseMessaging.getInstance().send(message);
            successCounter.increment();
            return messageId;
        } catch (FirebaseMessagingException e) {
            failureCounter.increment();
            throw e;
        }
    }

    /**
     * Best-effort broadcast to every device the user has a push token for.
     * Never throws — a push failure must not fail the caller (e.g. a payment callback).
     * Returns the number of messages successfully sent.
     */
    public int sendToUser(String username, Map<String, String> data) {
        if (!isConfigured()) {
            log.warn("[PUSH] Firebase not configured — skipping push for username={}", username);
            return 0;
        }
        return sendToSessions(sessionRepository.findByUsername(username), data, "username=" + username);
    }

    /**
     * Best-effort broadcast to every device of every (active) user in a business — a QRIS
     * callback payment rings all of the business's users' phones. Never throws.
     * Returns the number of messages successfully sent.
     */
    public int sendToBusiness(Long businessId, Map<String, String> data) {
        if (!isConfigured()) {
            log.warn("[PUSH] Firebase not configured — skipping push for businessId={}", businessId);
            return 0;
        }
        List<String> usernames = userRepository.findUsernamesByBusinessId(businessId);
        if (usernames.isEmpty()) {
            log.warn("[PUSH] No active users for businessId={} — nothing sent", businessId);
            return 0;
        }
        return sendToSessions(sessionRepository.findByUsernameIn(usernames), data, "businessId=" + businessId);
    }

    // Shared fan-out: send to each session's token, auto-clearing dead ones. Never throws.
    private int sendToSessions(List<Session> sessions, Map<String, String> data, String scope) {
        int withToken = 0;
        int sent = 0;
        for (Session s : sessions) {
            String token = s.getPushToken();
            if (token == null || token.isBlank()) continue;
            withToken++;
            try {
                String messageId = sendToToken(token, data);
                sent++;
                log.info("[PUSH] Sent to FCM deviceId={} messageId={}", s.getDeviceId(), messageId);
            } catch (FirebaseMessagingException e) {
                log.error("[PUSH] FCM rejected message for deviceId={} errorCode={}: {}",
                        s.getDeviceId(), e.getMessagingErrorCode(), e.getMessage(), e);
                // Auto-cleanup: drop a token FCM says is no longer registered
                if (isTokenUnregistered(e.getMessagingErrorCode())) {
                    s.setPushToken(null);
                    sessionRepository.save(s);
                    log.info("[PUSH] Cleared stale FCM token for deviceId={} ({})", s.getDeviceId(), scope);
                }
            } catch (RuntimeException e) {
                log.error("[PUSH] Unexpected error sending to deviceId={}: {}", s.getDeviceId(), e.getMessage(), e);
            }
        }
        if (withToken == 0) {
            log.warn("[PUSH] No device with an FCM token for {} — nothing sent", scope);
        } else {
            log.info("[PUSH] Delivered {} of {} push(es) for {}", sent, withToken, scope);
        }
        return sent;
    }
}
