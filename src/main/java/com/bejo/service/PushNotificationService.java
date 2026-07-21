package com.bejo.service;

import com.bejo.entity.Session;
import com.bejo.push.PushPayload;
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

import java.util.Collection;
import java.util.List;
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
 * <p>Every send takes a {@link PushPayload} (never a raw map): the payload owns its {@code type}
 * ({@link PushType}) and fields, and whether it needs {@link PushPayload#urgent() high-priority}
 * delivery — so a presence/ads push wakes the app promptly even in Doze while a payment can be
 * batched, with no per-caller map building or a separate "urgent" method.
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
    public String sendToToken(String pushToken, PushPayload payload) throws FirebaseMessagingException {
        return send(message(pushToken, payload));
    }

    /**
     * Best-effort broadcast to every device the user has a push token for. Delivery priority
     * comes from the payload ({@link PushPayload#urgent()}). Never throws — a push failure must
     * not fail the caller (e.g. a payment callback). Returns the number of messages sent.
     */
    public int sendToUser(String username, PushPayload payload) {
        if (!isConfigured()) {
            log.warn("[PUSH] Firebase not configured — skipping push for username={}", username);
            return 0;
        }
        return sendToSessions(sessionRepository.findByUsername(username),
                token -> message(token, payload), "username=" + username);
    }

    /**
     * Best-effort push to <b>one specific device</b> (a {@code (username, deviceId)} session) —
     * used by the ads flow, which targets a single device rather than a whole user or business.
     * Never throws; a dead token is auto-cleared. Returns the number of messages sent (0 or 1).
     */
    public int sendToDevice(String username, String deviceId, PushPayload payload) {
        if (!isConfigured()) {
            log.warn("[PUSH] Firebase not configured — skipping push for device={}/{}", username, deviceId);
            return 0;
        }
        List<Session> sessions = sessionRepository.findByUsernameAndDeviceId(username, deviceId)
                .map(List::of).orElseGet(List::of);
        return sendToSessions(sessions, token -> message(token, payload),
                "device=" + username + "/" + deviceId);
    }

    /**
     * Best-effort broadcast to every device of every (active) user in a business — a QRIS
     * callback payment rings all of the business's users' phones. Never throws.
     * Returns the number of messages successfully sent.
     */
    public int sendToBusiness(Long businessId, PushPayload payload) {
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
                token -> message(token, payload), "businessId=" + businessId);
    }

    /**
     * Best-effort broadcast to every device of a <b>specific set of users</b> — used by the payment
     * callback, which computes its recipients (by QRIS terminal id) rather than ringing the whole
     * business. Never throws. Returns the number of messages sent.
     */
    public int sendToUsernames(Collection<String> usernames, PushPayload payload) {
        if (!isConfigured()) {
            log.warn("[PUSH] Firebase not configured — skipping push for {} user(s)", usernames.size());
            return 0;
        }
        if (usernames.isEmpty()) {
            log.warn("[PUSH] No recipient users — nothing sent");
            return 0;
        }
        return sendToSessions(sessionRepository.findByUsernameIn(usernames),
                token -> message(token, payload), "usernames=" + usernames.size());
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
                // Expected + handled (e.g. UNREGISTERED cleans the token below) — log the code +
                // message, NOT the exception object, so the long FCM stack trace doesn't spam logs.
                log.warn("[PUSH] FCM rejected message for deviceId={} errorCode={}: {}",
                        s.getDeviceId(), e.getMessagingErrorCode(), e.getMessage());
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

    // One data-only Message from a payload. An urgent payload is flagged HIGH so FCM delivers it
    // at once instead of batching until the device next wakes — the app must act on it promptly.
    private Message message(String token, PushPayload payload) {
        Message.Builder builder = Message.builder().putAllData(payload.toData()).setToken(token);
        if (payload.urgent()) {
            builder.setAndroidConfig(AndroidConfig.builder().setPriority(AndroidConfig.Priority.HIGH).build());
        }
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
