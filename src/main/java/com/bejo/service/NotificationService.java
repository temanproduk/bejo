package com.bejo.service;

import com.bejo.dto.request.NotificationTransactionData;
import com.bejo.dto.request.SendNotificationRequest;
import com.bejo.entity.Session;
import com.bejo.entity.User;
import com.bejo.exception.ResourceNotFoundException;
import com.bejo.repository.SessionRepository;
import com.bejo.repository.UserRepository;
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
    private final UserRepository userRepository;
    private final PushNotificationService pushNotificationService;

    public NotificationService(SessionRepository sessionRepository,
                               UserRepository userRepository,
                               PushNotificationService pushNotificationService) {
        this.sessionRepository = sessionRepository;
        this.userRepository = userRepository;
        this.pushNotificationService = pushNotificationService;
    }

    public void send(String username, SendNotificationRequest request) throws FirebaseMessagingException {
        if (!pushNotificationService.isConfigured()) {
            throw new IllegalStateException("Firebase is not configured — set FIREBASE_CREDENTIALS_BASE64 or FIREBASE_CREDENTIALS_PATH");
        }

        String pushToken = sessionRepository.findByUsernameAndDeviceId(username, request.deviceId())
                .map(Session::getPushToken)
                .orElseThrow(() -> new ResourceNotFoundException("Device not found: " + request.deviceId()));

        if (pushToken == null || pushToken.isBlank()) {
            throw new ResourceNotFoundException("No FCM token registered for device: " + request.deviceId());
        }

        String merchantName = userRepository.findByUsername(username)
                .map(User::getBusinessName)
                .orElse(null);

        Map<String, String> data = buildData(request, request.data(), merchantName);

        log.info("[NOTIFY-TEST] Sending push to deviceId={} for username={}", request.deviceId(), username);
        try {
            String messageId = pushNotificationService.sendToToken(pushToken, data);
            log.info("[NOTIFY-TEST] Push sent to FCM successfully deviceId={} messageId={}", request.deviceId(), messageId);
        } catch (FirebaseMessagingException e) {
            log.error("[NOTIFY-TEST] Push to FCM FAILED deviceId={} errorCode={}: {}",
                    request.deviceId(), e.getMessagingErrorCode(), e.getMessage(), e);
            throw e;
        }
    }

    private Map<String, String> buildData(SendNotificationRequest request, NotificationTransactionData txData, String merchantName) {
        Map<String, String> data = new HashMap<>();
        data.put("title", request.title());
        data.put("body", request.body());
        data.put("transactionId", txData.transactionId());
        data.put("transactionDate", txData.transactionDate().toString());
        data.put("amount", String.valueOf(txData.amount()));
        data.put("issuerName", txData.issuerName());
        if (merchantName != null) data.put("merchantName", merchantName);
        if (txData.merchantPan() != null) data.put("merchantPan", txData.merchantPan());
        if (txData.customerPan() != null) data.put("customerPan", txData.customerPan());
        if (txData.rrn() != null) data.put("rrn", txData.rrn());
        return data;
    }
}
