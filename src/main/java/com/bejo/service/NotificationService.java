package com.bejo.service;

import com.bejo.dto.request.NotificationTransactionData;
import com.bejo.dto.request.SendNotificationRequest;
import com.bejo.entity.Transaction;
import com.bejo.exception.ResourceNotFoundException;
import com.bejo.repository.SessionRepository;
import com.bejo.repository.TransactionRepository;
import com.bejo.repository.UserRepository;
import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

@Service
public class NotificationService {

    private static final Logger log = LoggerFactory.getLogger(NotificationService.class);

    private final SessionRepository sessionRepository;
    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final Counter notificationSuccessCounter;
    private final Counter notificationFailureCounter;

    public NotificationService(SessionRepository sessionRepository,
                               TransactionRepository transactionRepository,
                               UserRepository userRepository,
                               MeterRegistry meterRegistry) {
        this.sessionRepository = sessionRepository;
        this.transactionRepository = transactionRepository;
        this.userRepository = userRepository;
        this.notificationSuccessCounter = Counter.builder("notifications.sent")
                .tag("result", "success")
                .register(meterRegistry);
        this.notificationFailureCounter = Counter.builder("notifications.sent")
                .tag("result", "failure")
                .register(meterRegistry);
    }

    @Transactional
    public void send(String username, SendNotificationRequest request) throws FirebaseMessagingException {
        if (FirebaseApp.getApps().isEmpty()) {
            throw new IllegalStateException("Firebase is not configured — set FIREBASE_CREDENTIALS_BASE64 or FIREBASE_CREDENTIALS_PATH");
        }

        NotificationTransactionData txData = request.data();

        // All pre-flight checks before any side effect
        if (transactionRepository.existsByTransactionId(txData.transactionId())) {
            throw new IllegalArgumentException("transactionId sudah terdaftar: " + txData.transactionId());
        }

        String pushToken = sessionRepository.findByDeviceId(request.deviceId())
                .map(s -> s.getPushToken())
                .orElseThrow(() -> new ResourceNotFoundException("Device not found: " + request.deviceId()));

        if (pushToken == null || pushToken.isBlank()) {
            throw new ResourceNotFoundException("No FCM token registered for device: " + request.deviceId());
        }

        String merchantName = userRepository.findByUsername(username)
                .map(u -> u.getBusinessName())
                .orElse(null);

        Map<String, String> fcmData = new HashMap<>();
        fcmData.put("title", request.title());
        fcmData.put("body", request.body());
        fcmData.put("transactionId", txData.transactionId());
        fcmData.put("transactionDate", txData.transactionDate().toString());
        fcmData.put("amount", String.valueOf(txData.amount()));
        fcmData.put("issuerName", txData.issuerName());
        if (merchantName != null) fcmData.put("merchantName", merchantName);
        if (txData.merchantPan() != null) fcmData.put("merchantPan", txData.merchantPan());
        if (txData.customerPan() != null) fcmData.put("customerPan", txData.customerPan());
        if (txData.rrn() != null) fcmData.put("rrn", txData.rrn());

        Message message = Message.builder()
                .putAllData(fcmData)
                .setToken(pushToken)
                .build();

        log.info("[NOTIFY] Sending to deviceId={}", request.deviceId());

        try {
            String messageId = FirebaseMessaging.getInstance().send(message);
            notificationSuccessCounter.increment();
            log.info("[NOTIFY] Sent successfully messageId={}", messageId);
        } catch (FirebaseMessagingException e) {
            notificationFailureCounter.increment();
            log.error("[NOTIFY] Failed to send to deviceId={}: {}", request.deviceId(), e.getMessage());
            throw e;
        }

        saveTransaction(username, txData);
    }

    private void saveTransaction(String username, NotificationTransactionData txData) {
        Transaction t = new Transaction();
        t.setTransactionId(txData.transactionId());
        t.setTransactionDate(txData.transactionDate());
        t.setAmount(txData.amount());
        t.setSource(txData.issuerName());
        t.setUsername(username);
        t.setMerchantPan(txData.merchantPan());
        t.setCustomerPan(txData.customerPan());
        t.setRrn(txData.rrn());
        t.setStatus("SUCCESS");
        t.setScrapeDate(LocalDateTime.now());
        transactionRepository.save(t);
        log.info("[NOTIFY] Transaction saved transactionId={}", txData.transactionId());
    }
}
