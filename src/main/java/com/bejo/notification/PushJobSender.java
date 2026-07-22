package com.bejo.notification;

import com.google.firebase.messaging.AndroidConfig;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * The consumer side of push delivery: turns a {@link PushJob} into an FCM message and sends it.
 * Independent of <em>how</em> the job arrived — the in-memory queue's worker threads call it today,
 * and a future Kafka/Redis consumer would call the exact same {@link #deliver}. Owns the FCM call,
 * the send metrics, and dead-token cleanup.
 *
 * <p><b>Every message is data-only</b> (no FCM {@code notification} block) so the app always gets
 * {@code onMessageReceived} and renders the push itself; an urgent job is flagged Android priority
 * HIGH so FCM wakes the app promptly.
 */
@Component
public class PushJobSender {

    private static final Logger log = LoggerFactory.getLogger(PushJobSender.class);

    private final StaleTokenHandler staleTokenHandler;
    private final Counter successCounter;
    private final Counter failureCounter;

    public PushJobSender(StaleTokenHandler staleTokenHandler, MeterRegistry meterRegistry) {
        this.staleTokenHandler = staleTokenHandler;
        this.successCounter = Counter.builder("notifications.sent").tag("result", "success").register(meterRegistry);
        this.failureCounter = Counter.builder("notifications.sent").tag("result", "failure").register(meterRegistry);
    }

    /**
     * True when FCM's error means the token is dead and should be removed — the device unregistered
     * (app uninstalled / token rotated → "Requested entity was not found") or the token belongs to a
     * different Firebase sender. Retrying it is pointless.
     */
    public static boolean isTokenUnregistered(MessagingErrorCode code) {
        return code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.SENDER_ID_MISMATCH;
    }

    /** Deliver a queued job (the async path). Never throws; a dead token is auto-cleared. */
    public void deliver(PushJob job) {
        try {
            String messageId = sendRaw(buildMessage(job.token(), job.data(), job.urgent()));
            log.debug("[PUSH] Sent to FCM deviceId={} messageId={}", job.deviceId(), messageId);
        } catch (FirebaseMessagingException e) {
            // Expected + handled (e.g. UNREGISTERED cleans the token below) — log the code + message,
            // NOT the exception object, so the long FCM stack trace doesn't spam logs.
            log.warn("[PUSH] FCM rejected message for deviceId={} errorCode={}: {}",
                    job.deviceId(), e.getMessagingErrorCode(), e.getMessage());
            if (isTokenUnregistered(e.getMessagingErrorCode())) {
                staleTokenHandler.onTokenRejected(job.recipientRef(), job.token());
                log.info("[PUSH] Cleared stale FCM token for deviceId={} ({})", job.deviceId(), job.scope());
            }
        } catch (RuntimeException e) {
            log.error("[PUSH] Unexpected error sending to deviceId={}: {}", job.deviceId(), e.getMessage(), e);
        }
    }

    /** Send one message synchronously and propagate the FCM error — for the test-notification endpoint. */
    public String sendSync(String token, Map<String, String> data, boolean urgent) throws FirebaseMessagingException {
        return sendRaw(buildMessage(token, data, urgent));
    }

    private Message buildMessage(String token, Map<String, String> data, boolean urgent) {
        Message.Builder builder = Message.builder().putAllData(data).setToken(token);
        if (urgent) {
            builder.setAndroidConfig(AndroidConfig.builder().setPriority(AndroidConfig.Priority.HIGH).build());
        }
        return builder.build();
    }

    private String sendRaw(Message message) throws FirebaseMessagingException {
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
