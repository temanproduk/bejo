package com.bejo.service;

import com.bejo.dto.request.BroadcastNotificationRequestDto;
import com.bejo.dto.request.NotificationTransactionRequestDto;
import com.bejo.dto.request.SendNotificationRequestDto;
import com.bejo.entity.Business;
import com.bejo.entity.Session;
import com.bejo.exception.ResourceNotFoundException;
import com.bejo.push.PushPayload;
import com.bejo.push.SimplePushPayload;
import com.bejo.repository.BusinessRepository;
import com.bejo.repository.SessionRepository;
import com.google.firebase.messaging.FirebaseMessagingException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * Test-notification endpoint: sends an FCM push built from the request payload to a
 * single registered device. It does NOT record a transaction — it is purely for
 * verifying push delivery. (Real payments are recorded by the QRIS callback flow.)
 */
@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final SessionRepository sessionRepository;
    private final BusinessRepository businessRepository;
    private final BusinessContext businessContext;
    private final PushNotificationService pushNotificationService;

    public NotificationService(SessionRepository sessionRepository,
                               BusinessRepository businessRepository,
                               BusinessContext businessContext,
                               PushNotificationService pushNotificationService) {
        this.sessionRepository = sessionRepository;
        this.businessRepository = businessRepository;
        this.businessContext = businessContext;
        this.pushNotificationService = pushNotificationService;
    }

    public void send(String username, SendNotificationRequestDto request) throws FirebaseMessagingException {
        if (!pushNotificationService.isConfigured()) {
            throw new IllegalStateException("Firebase is not configured — set FIREBASE_CREDENTIALS_BASE64 or FIREBASE_CREDENTIALS_PATH");
        }

        String pushToken = sessionRepository.findByUsernameAndDeviceId(username, request.deviceId())
                .map(Session::getPushToken)
                .orElseThrow(() -> new ResourceNotFoundException("Perangkat tidak ditemukan: " + request.deviceId()));

        if (pushToken == null || pushToken.isBlank()) {
            throw new ResourceNotFoundException("Perangkat belum terdaftar notifikasi: " + request.deviceId());
        }

        PushPayload payload = buildPayload(request.title(), request.body(), request.data(), merchantName(username));

        log.info("[NOTIFY-TEST] Sending push to deviceId={} for username={}", request.deviceId(), username);
        try {
            String messageId = pushNotificationService.sendToToken(pushToken, payload);
            log.info("[NOTIFY-TEST] Push sent to FCM successfully deviceId={} messageId={}", request.deviceId(), messageId);
        } catch (FirebaseMessagingException e) {
            // Code + message only (no stack trace) — keeps the long FCM trace out of the logs.
            log.warn("[NOTIFY-TEST] Push to FCM FAILED deviceId={} errorCode={}: {}",
                    request.deviceId(), e.getMessagingErrorCode(), e.getMessage());
            // Auto-cleanup: drop the token if FCM says it's no longer registered
            if (PushNotificationService.isTokenUnregistered(e.getMessagingErrorCode())) {
                sessionRepository.findByUsernameAndDeviceId(username, request.deviceId()).ifPresent(s -> {
                    s.setPushToken(null);
                    sessionRepository.save(s);
                    log.info("[NOTIFY-TEST] Cleared stale FCM token for deviceId={} username={}", request.deviceId(), username);
                });
            }
            throw e;
        }
    }

    /**
     * Test/utility: broadcast an FCM push to <b>all</b> of the caller's devices (best-effort
     * via {@link PushNotificationService#sendToUser}, with stale-token auto-cleanup).
     * Returns the number of devices the push was delivered to. Does not record a transaction.
     */
    public int broadcastToUser(String username, BroadcastNotificationRequestDto request) {
        if (!pushNotificationService.isConfigured()) {
            throw new IllegalStateException("Firebase is not configured — set FIREBASE_CREDENTIALS_BASE64 or FIREBASE_CREDENTIALS_PATH");
        }
        PushPayload payload = buildPayload(request.title(), request.body(), request.data(), merchantName(username));
        log.info("[NOTIFY-USER] Broadcasting push to all devices of username={}", username);
        return pushNotificationService.sendToUser(username, payload);
    }

    private String merchantName(String username) {
        Long businessId = businessContext.requireBusinessId(username);
        return businessRepository.findById(businessId).map(Business::getName).orElse(null);
    }

    // A test push carrying transaction-shaped fields (no fixed PushType — it's a delivery probe).
    private PushPayload buildPayload(String title, String body, NotificationTransactionRequestDto txData, String merchantName) {
        Map<String, String> extra = new HashMap<>();
        extra.put("transactionId", txData.transactionId());
        extra.put("transactionDate", txData.transactionDate().toString());
        extra.put("amount", String.valueOf(txData.amount()));
        extra.put("issuerName", txData.issuerName());
        if (merchantName != null) extra.put("merchantName", merchantName);
        if (txData.merchantPan() != null) extra.put("merchantPan", txData.merchantPan());
        if (txData.customerPan() != null) extra.put("customerPan", txData.customerPan());
        if (txData.rrn() != null) extra.put("rrn", txData.rrn());
        return new SimplePushPayload(null, title, body, extra);
    }
}
