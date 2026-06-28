package com.bejo.service;

import com.bejo.dto.request.SendNotificationRequest;
import com.bejo.exception.ResourceNotFoundException;
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

import java.util.HashMap;
import java.util.Map;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final SessionRepository sessionRepository;
    private final Counter notificationSuccessCounter;
    private final Counter notificationFailureCounter;

    public NotificationService(SessionRepository sessionRepository, MeterRegistry meterRegistry) {
        this.sessionRepository = sessionRepository;
        this.notificationSuccessCounter = Counter.builder("notifications.sent")
                .tag("result", "success")
                .register(meterRegistry);
        this.notificationFailureCounter = Counter.builder("notifications.sent")
                .tag("result", "failure")
                .register(meterRegistry);
    }

    public void send(SendNotificationRequest request) throws FirebaseMessagingException {
        if (FirebaseApp.getApps().isEmpty()) {
            throw new IllegalStateException("Firebase is not configured — set FIREBASE_CREDENTIALS_BASE64 or FIREBASE_CREDENTIALS_PATH");
        }

        String pushToken = sessionRepository.findByDeviceId(request.deviceId())
                .map(s -> s.getPushToken())
                .orElseThrow(() -> new ResourceNotFoundException("Device not found: " + request.deviceId()));

        if (pushToken == null || pushToken.isBlank()) {
            throw new ResourceNotFoundException("No FCM token registered for device: " + request.deviceId());
        }

        Map<String, String> data = new HashMap<>();
        data.put("title", request.title());
        data.put("body", request.body());
        if (request.data() != null) {
            data.putAll(request.data());
        }

        Message message = Message.builder()
                .putAllData(data)
                .setToken(pushToken)
                .build();

        log.info("[NOTIFY] Sending notification to deviceId={}", request.deviceId());

        try {
            String messageId = FirebaseMessaging.getInstance().send(message);
            notificationSuccessCounter.increment();
            log.info("[NOTIFY] Sent successfully, messageId={}", messageId);
        } catch (FirebaseMessagingException e) {
            notificationFailureCounter.increment();
            log.error("[NOTIFY] Failed to send notification to deviceId={}: {}", request.deviceId(), e.getMessage());
            throw e;
        }
    }
}
