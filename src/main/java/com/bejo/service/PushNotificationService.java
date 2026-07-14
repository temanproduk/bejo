package com.bejo.service;

import com.bejo.entity.Session;
import com.bejo.repository.SessionRepository;
import com.bejo.repository.UserRepository;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Low-level FCM sender shared by the callback flow, the presence flow and the
 * test-notification API. Does not touch the database (beyond clearing dead tokens).
 *
 * <p>Two message shapes: <b>data-only</b> ({@code sendToToken}/{@code sendToUser}/
 * {@code sendToBusiness}) — the app builds the notification itself, used for payments;
 * and <b>notification</b> ({@code sendNotificationTo…}) — a title/body FCM displays
 * directly, used for presence clock-in/out and pings.
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

    /** Send a data-only message to a single device token. Propagates FirebaseMessagingException. */
    public String sendToToken(String pushToken, Map<String, String> data) throws FirebaseMessagingException {
        return send(dataMessage(pushToken, data));
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
        return sendToSessions(sessionRepository.findByUsername(username),
                token -> dataMessage(token, data), "username=" + username);
    }

    /**
     * Best-effort display-notification broadcast to every device of one user (presence
     * clock-in/out → the super admin; a ping → the member). FCM renders title/body itself;
     * {@code data} rides along only so the app can route the tap. Never throws.
     */
    public int sendNotificationToUser(String username, String title, String body, Map<String, String> data) {
        if (!isConfigured()) {
            log.warn("[PUSH] Firebase not configured — skipping notification for username={}", username);
            return 0;
        }
        return sendToSessions(sessionRepository.findByUsername(username),
                token -> notificationMessage(token, title, body, data), "username=" + username);
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
        return sendToSessions(sessionRepository.findByUsernameIn(usernames),
                token -> dataMessage(token, data), "businessId=" + businessId);
    }

    // Shared fan-out: send each session's token its own Message, auto-clearing dead ones. Never throws.
    private int sendToSessions(List<Session> sessions, Function<String, Message> message, String scope) {
        int withToken = 0;
        int sent = 0;
        for (Session s : sessions) {
            String token = s.getPushToken();
            if (token == null || token.isBlank()) continue;
            withToken++;
            try {
                String messageId = send(message.apply(token));
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

    private Message dataMessage(String token, Map<String, String> data) {
        return Message.builder().putAllData(data).setToken(token).build();
    }

    private Message notificationMessage(String token, String title, String body, Map<String, String> data) {
        Message.Builder builder = Message.builder()
                .setNotification(Notification.builder().setTitle(title).setBody(body).build())
                .setToken(token);
        if (data != null) builder.putAllData(data);
        return builder.build();
    }

    private String send(Message message) throws FirebaseMessagingException {
        try {
            String messageId = FirebaseMessaging.getInstance().send(message);
            successCounter.increment();
            return messageId;
        } catch (FirebaseMessagingException e) {
            failureCounter.increment();
            throw e;
        }
    }
}
