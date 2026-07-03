package com.bejo.service;

import com.bejo.entity.Session;
import com.bejo.repository.SessionRepository;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
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
    private final Counter successCounter;
    private final Counter failureCounter;

    public PushNotificationService(SessionRepository sessionRepository, MeterRegistry meterRegistry) {
        this.sessionRepository = sessionRepository;
        this.successCounter = Counter.builder("notifications.sent").tag("result", "success").register(meterRegistry);
        this.failureCounter = Counter.builder("notifications.sent").tag("result", "failure").register(meterRegistry);
    }

    public boolean isConfigured() {
        return !FirebaseApp.getApps().isEmpty();
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
        List<Session> sessions = sessionRepository.findByUsername(username);
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
            } catch (RuntimeException e) {
                log.error("[PUSH] Unexpected error sending to deviceId={}: {}", s.getDeviceId(), e.getMessage(), e);
            }
        }
        if (withToken == 0) {
            log.warn("[PUSH] No device with an FCM token for username={} — nothing sent", username);
        } else {
            log.info("[PUSH] Delivered {} of {} push(es) for username={}", sent, withToken, username);
        }
        return sent;
    }
}
