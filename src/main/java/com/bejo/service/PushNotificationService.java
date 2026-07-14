package com.bejo.service;

import com.bejo.entity.Session;
import com.bejo.repository.SessionRepository;
import com.bejo.repository.UserRepository;
import com.bejo.constant.PushType;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.AndroidConfig;
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
import java.util.function.Function;

/**
 * Low-level FCM sender shared by the callback flow, the presence flow and the
 * test-notification API. Does not touch the database (beyond clearing dead tokens).
 *
 * <p><b>Every message is data-only</b> — no FCM {@code notification} block. A notification
 * message is drawn by the system tray and never reaches {@code onMessageReceived} while the
 * app is backgrounded, so the app gets no code execution and can't react (pop a dialog,
 * decide how to alert). Data-only hands every delivery to the app instead. The tradeoff is
 * that a <b>force-stopped</b> app displays nothing at all — an accepted cost, since the
 * point of these pushes is for the app to act on them.
 *
 * <p>The payload's {@code type} ({@link PushType}) is how the app tells a payment from an
 * attendance event. Presence pushes additionally go out at {@link #sendUrgentToUser high
 * priority}, which wakes the app promptly even in Doze.
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
     * Best-effort data-only broadcast to every device of one user, at <b>high priority</b> —
     * used by the presence flow (a clock-in/out → the super admin; a ping → the member).
     * High priority tells FCM to deliver immediately and wake the app even in Doze, so it can
     * show its dialog / build its own heads-up notification on arrival. Never throws.
     */
    public int sendUrgentToUser(String username, Map<String, String> data) {
        if (!isConfigured()) {
            log.warn("[PUSH] Firebase not configured — skipping push for username={}", username);
            return 0;
        }
        return sendToSessions(sessionRepository.findByUsername(username),
                token -> urgentDataMessage(token, data), "username=" + username);
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

    // Same data-only shape, but flagged HIGH so FCM delivers it at once instead of batching
    // it until the device next wakes — the app has to act on a presence event promptly.
    private Message urgentDataMessage(String token, Map<String, String> data) {
        return Message.builder()
                .putAllData(data)
                .setAndroidConfig(AndroidConfig.builder().setPriority(AndroidConfig.Priority.HIGH).build())
                .setToken(token)
                .build();
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
