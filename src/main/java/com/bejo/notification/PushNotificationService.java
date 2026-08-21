package com.bejo.notification;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.MessagingErrorCode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.List;
import java.util.Map;

/**
 * The <b>producer</b> side of FCM delivery: resolves which devices a push should reach (from the
 * request/callback thread) and hands each off to the {@link PushDeliveryQueue} as a {@link PushJob}.
 * The actual FCM call runs later on the queue's workers ({@link PushJobSender}), so nothing here
 * blocks on the network. Shared by the callback, presence, ads, and test-notification flows.
 *
 * <p><b>Owns no session or user knowledge.</b> Addressing (username / device / business → devices)
 * is resolved through the {@link PushRecipientDirectory} port, which core implements — so this
 * module never imports session or repository code and the module graph stays acyclic.
 *
 * <p><b>Every message is data-only</b> — no FCM {@code notification} block. A notification message
 * is drawn by the system tray and never reaches {@code onMessageReceived} while the app is
 * backgrounded, so the app gets no code execution and can't react. Data-only hands every delivery
 * to the app. The payload ({@link PushPayload}) owns its {@code type} ({@link PushType}), its
 * fields, and whether it needs {@link PushPayload#urgent() high-priority} delivery.
 */
@Service
public class PushNotificationService {

    private static final Logger log = LoggerFactory.getLogger(PushNotificationService.class);

    private final PushRecipientDirectory recipientDirectory;
    private final PushDeliveryQueue pushQueue;
    private final PushJobSender pushJobSender;

    public PushNotificationService(PushRecipientDirectory recipientDirectory,
                                   PushDeliveryQueue pushQueue,
                                   PushJobSender pushJobSender) {
        this.recipientDirectory = recipientDirectory;
        this.pushQueue = pushQueue;
        this.pushJobSender = pushJobSender;
    }

    public boolean isConfigured() {
        return !FirebaseApp.getApps().isEmpty();
    }

    /** @see PushJobSender#isTokenUnregistered — kept here for callers already importing this service. */
    public static boolean isTokenUnregistered(MessagingErrorCode code) {
        return PushJobSender.isTokenUnregistered(code);
    }

    /** Send a data-only message to a single device token, synchronously. Propagates FirebaseMessagingException. */
    public String sendToToken(String pushToken, PushPayload payload) throws FirebaseMessagingException {
        return pushJobSender.sendSync(pushToken, payload.toData(), payload.urgent());
    }

    /**
     * Best-effort broadcast to every device the user has a push token for. Never throws — a push
     * failure must not fail the caller. Returns the number of messages <b>queued</b>.
     */
    public int sendToUser(String username, PushPayload payload) {
        if (!isConfigured()) {
            log.warn("[PUSH] Firebase not configured — skipping push for username={}", username);
            return 0;
        }
        return enqueue(recipientDirectory.forUser(username), payload, "username=" + username);
    }

    /**
     * Best-effort push to <b>one specific device</b> (a {@code (username, deviceId)} session) — used
     * by the ads flow, which targets a single device. Never throws. Returns the number queued (0 or 1).
     */
    public int sendToDevice(String username, String deviceId, PushPayload payload) {
        if (!isConfigured()) {
            log.warn("[PUSH] Firebase not configured — skipping push for device={}/{}", username, deviceId);
            return 0;
        }
        return enqueue(recipientDirectory.forDevice(username, deviceId), payload, "device=" + username + "/" + deviceId);
    }

    /**
     * Best-effort broadcast to every device of every (active) user in a business. Never throws.
     * Returns the number of messages queued.
     */
    public int sendToBusiness(Long businessId, PushPayload payload) {
        if (!isConfigured()) {
            log.warn("[PUSH] Firebase not configured — skipping push for businessId={}", businessId);
            return 0;
        }
        return enqueue(recipientDirectory.forBusiness(businessId), payload, "businessId=" + businessId);
    }

    /**
     * Best-effort broadcast to every device of a <b>specific set of users</b> — the payment callback,
     * which computes its recipients (by QRIS terminal id). Never throws. Returns the number queued.
     */
    public int sendToUsernames(Collection<String> usernames, PushPayload payload) {
        return sendToUsernames(usernames, payload, null);
    }

    /**
     * @param excludeDeviceId a single device to leave out, or null for none. Exists because the
     *   device that reported a payment has already announced it locally — but the same account's
     *   <em>other</em> devices have not, so the exclusion has to be per-device. Dropping the whole
     *   username instead silenced every phone the merchant owns.
     */
    public int sendToUsernames(Collection<String> usernames, PushPayload payload, String excludeDeviceId) {
        if (!isConfigured()) {
            log.warn("[PUSH] Firebase not configured — skipping push for {} user(s)", usernames.size());
            return 0;
        }
        if (usernames.isEmpty()) {
            log.warn("[PUSH] No recipient users — nothing sent");
            return 0;
        }
        List<PushRecipient> recipients = recipientDirectory.forUsernames(usernames);
        if (excludeDeviceId != null && !excludeDeviceId.isBlank()) {
            recipients = recipients.stream()
                    .filter(r -> !excludeDeviceId.equals(r.deviceId()))
                    .toList();
        }
        return enqueue(recipients, payload, "usernames=" + usernames.size());
    }

    // Build the payload once, then enqueue a job per resolved recipient. Returns how many were
    // queued (the actual sends happen later on the delivery queue's workers). The directory has
    // already dropped token-less devices, so every recipient here is deliverable.
    private int enqueue(List<PushRecipient> recipients, PushPayload payload, String scope) {
        if (recipients.isEmpty()) {
            log.warn("[PUSH] No device with an FCM token for {} — nothing queued", scope);
            return 0;
        }
        Map<String, String> data = payload.toData();
        boolean urgent = payload.urgent();
        for (PushRecipient r : recipients) {
            pushQueue.enqueue(new PushJob(r.token(), data, urgent, r.recipientRef(), r.deviceId(), scope));
        }
        log.debug("[PUSH] Queued {} push(es) for {}", recipients.size(), scope);
        return recipients.size();
    }
}
