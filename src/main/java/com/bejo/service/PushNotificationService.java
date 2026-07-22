package com.bejo.service;

import com.bejo.constant.PushType;
import com.bejo.entity.Session;
import com.bejo.push.PushDeliveryQueue;
import com.bejo.push.PushJob;
import com.bejo.push.PushJobSender;
import com.bejo.push.PushPayload;
import com.bejo.repository.SessionRepository;
import com.bejo.repository.UserRepository;
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
 * blocks on the network. Shared by the callback, presence, ads, and test-notification flows; does
 * not touch the database (session reads for addressing only — token cleanup is on the consumer).
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

    private final SessionRepository sessionRepository;
    private final UserRepository userRepository;
    private final PushDeliveryQueue pushQueue;
    private final PushJobSender pushJobSender;

    public PushNotificationService(SessionRepository sessionRepository,
                                   UserRepository userRepository,
                                   PushDeliveryQueue pushQueue,
                                   PushJobSender pushJobSender) {
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
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
        return enqueueForSessions(sessionRepository.findByUsername(username), payload, "username=" + username);
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
        List<Session> sessions = sessionRepository.findByUsernameAndDeviceId(username, deviceId)
                .map(List::of).orElseGet(List::of);
        return enqueueForSessions(sessions, payload, "device=" + username + "/" + deviceId);
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
        List<String> usernames = userRepository.findUsernamesByBusinessId(businessId);
        if (usernames.isEmpty()) {
            log.warn("[PUSH] No active users for businessId={} — nothing sent", businessId);
            return 0;
        }
        return enqueueForSessions(sessionRepository.findByUsernameIn(usernames), payload, "businessId=" + businessId);
    }

    /**
     * Best-effort broadcast to every device of a <b>specific set of users</b> — the payment callback,
     * which computes its recipients (by QRIS terminal id). Never throws. Returns the number queued.
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
        return enqueueForSessions(sessionRepository.findByUsernameIn(usernames), payload, "usernames=" + usernames.size());
    }

    // Build the payload once, then enqueue a job per session that has a token. Returns how many
    // were queued (the actual sends happen later on the delivery queue's workers).
    private int enqueueForSessions(List<Session> sessions, PushPayload payload, String scope) {
        Map<String, String> data = payload.toData();
        boolean urgent = payload.urgent();
        int withToken = 0;
        for (Session s : sessions) {
            String token = s.getPushToken();
            if (token == null || token.isBlank()) continue;
            withToken++;
            pushQueue.enqueue(new PushJob(token, data, urgent, s.getId(), s.getDeviceId(), scope));
        }
        if (withToken == 0) {
            log.warn("[PUSH] No device with an FCM token for {} — nothing queued", scope);
        } else {
            log.debug("[PUSH] Queued {} push(es) for {}", withToken, scope);
        }
        return withToken;
    }
}
