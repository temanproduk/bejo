package com.bejo.service;

import com.bejo.constant.Acquirer;
import com.bejo.constant.TransactionStatus;
import com.bejo.constant.TransactionType;
import com.bejo.dto.request.QrMpmNotifyRequestDto;
import com.bejo.dto.response.QrMpmNotifyResponseDto;
import com.bejo.entity.Transaction;
import com.bejo.entity.User;
import com.bejo.exception.ResourceNotFoundException;
import com.bejo.repository.TransactionRepository;
import com.bejo.repository.UserRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.HashMap;
import java.util.Map;

@Service
public class QrisCallbackService {

    private static final Logger log = LoggerFactory.getLogger(QrisCallbackService.class);

    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final PushNotificationService pushNotificationService;
    private final ObjectMapper objectMapper;
    private final Counter callbackCounter;

    // Callbacks that don't match a merchant by NMID fall back to this user
    // (defaults to the seed user). Lets single-merchant deployments work
    // out of the box while still routing by NMID when it matches.
    @Value("${app.callback.default-username:admin}")
    private String defaultUsername;

    public QrisCallbackService(TransactionRepository transactionRepository,
                               UserRepository userRepository,
                               PushNotificationService pushNotificationService,
                               ObjectMapper objectMapper,
                               MeterRegistry meterRegistry) {
        this.transactionRepository = transactionRepository;
        this.userRepository = userRepository;
        this.pushNotificationService = pushNotificationService;
        this.objectMapper = objectMapper;
        this.callbackCounter = Counter.builder("qris.callbacks.received").register(meterRegistry);
    }

    @Transactional
    public QrMpmNotifyResponseDto processNotify(QrMpmNotifyRequestDto req, String xTimestamp) {
        callbackCounter.increment();
        log.info("[CALLBACK] originalReferenceNo={} partnerRef={} status={}",
                req.originalReferenceNo(), req.originalPartnerReferenceNo(), req.latestTransactionStatus());

        QrMpmNotifyRequestDto.AdditionalInfo info = req.additionalInfo();
        String merchantPan = info != null && info.merchantPan() != null ? info.merchantPan() : req.destinationNumber();

        // Route to the merchant whose NMID matches, else the default user
        User merchant = userRepository.findFirstByNmid(merchantPan)
                .or(() -> userRepository.findByUsername(defaultUsername))
                .orElseThrow(() -> {
                    log.error("[CALLBACK] No merchant for merchantPan={} and default user '{}' missing — "
                                    + "payment NOT recorded (originalReferenceNo={})",
                            merchantPan, defaultUsername, req.originalReferenceNo());
                    return new ResourceNotFoundException(
                            "No merchant matches merchantPan=" + merchantPan
                                    + " and default user '" + defaultUsername + "' not found");
                });
        String username = merchant.getUsername();

        BigDecimal amount = parseAmount(req.amount().value());
        BigDecimal tip = info != null && info.tip() != null ? parseAmount(info.tip()) : BigDecimal.ZERO;
        String idempotencyKey = req.originalReferenceNo() + "|" + req.originalPartnerReferenceNo();

        // Idempotency — callbacks may be delivered more than once
        var existing = transactionRepository.findByIdempotencyKeyAndUsername(idempotencyKey, username);
        if (existing.isPresent()) {
            Transaction t = existing.get();
            log.info("[CALLBACK] Idempotent replay key={} transactionId={}", idempotencyKey, t.getTransactionId());
            return response(t.getId(), req, amount, tip);
        }

        String status = mapStatus(req.latestTransactionStatus());
        String transactionId = info != null && info.transactionId() != null
                ? info.transactionId() : req.originalReferenceNo();

        Transaction t = new Transaction();
        t.setTransactionId(transactionId);
        t.setIdempotencyKey(idempotencyKey);
        t.setInvoiceId(req.originalReferenceNo());
        t.setTransactionDate(parseTimestamp(xTimestamp));
        t.setAmount(amount.setScale(0, RoundingMode.HALF_UP).longValueExact());
        t.setStatus(status);
        t.setUsername(username);
        t.setType(TransactionType.MANAGED);
        t.setAcquirer(info != null && info.deviceId() != null ? info.deviceId() : Acquirer.ASTRAPAY);
        t.setSource(info != null ? info.issuerName() : null);
        t.setMerchantPan(merchantPan);
        t.setCustomerPan(info != null && info.customerPan() != null ? info.customerPan() : req.customerNumber());
        t.setCustomerName(req.destinationAccountName());
        t.setScrapeDate(LocalDateTime.now());
        t.setAdditionalInfo(toJson(req));
        transactionRepository.save(t);
        log.info("[CALLBACK] Recorded transactionId={} username={} amount={} status={}",
                transactionId, username, t.getAmount(), status);

        // Best-effort push to the merchant's devices — must never fail the callback
        try {
            pushNotificationService.sendToUser(username, buildPushData(t, merchant.getBusinessName()));
        } catch (RuntimeException e) {
            log.error("[CALLBACK] Push step failed for transactionId={} (transaction still recorded): {}",
                    transactionId, e.getMessage(), e);
        }

        return response(t.getId(), req, amount, tip);
    }

    private Map<String, String> buildPushData(Transaction t, String merchantName) {
        Map<String, String> data = new HashMap<>();
        data.put("title", "Pembayaran Diterima");
        data.put("body", "Pembayaran Rp" + t.getAmount() + " diterima");
        data.put("transactionId", t.getTransactionId());
        if (t.getTransactionDate() != null) data.put("transactionDate", t.getTransactionDate().toString());
        data.put("amount", String.valueOf(t.getAmount()));
        data.put("status", t.getStatus());
        if (t.getSource() != null) data.put("issuerName", t.getSource());
        if (merchantName != null) data.put("merchantName", merchantName);
        if (t.getMerchantPan() != null) data.put("merchantPan", t.getMerchantPan());
        if (t.getCustomerPan() != null) data.put("customerPan", t.getCustomerPan());
        return data;
    }

    // Per the spec, response.status is the acknowledgment status (processing
    // succeeded), not the payment's status — its example hardcodes "SUCCESS".
    private QrMpmNotifyResponseDto response(Long id, QrMpmNotifyRequestDto req, BigDecimal amount, BigDecimal tip) {
        return new QrMpmNotifyResponseDto(
                id,
                req.originalReferenceNo(),
                req.originalPartnerReferenceNo(),
                amount,
                tip,
                TransactionStatus.SUCCESS,
                LocalDateTime.now());
    }

    private BigDecimal parseAmount(String value) {
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            log.error("[CALLBACK] Invalid amount value '{}' in callback payload", value);
            throw new IllegalArgumentException("Nilai amount tidak valid: " + value);
        }
    }

    // 00=SUCCESS, 01=PENDING, 02=FAILED, 03=REFUNDED, 04=CANCELLED, 05=EXPIRED
    private String mapStatus(String code) {
        return switch (code) {
            case "00" -> TransactionStatus.SUCCESS;
            case "01" -> TransactionStatus.PENDING;
            case "02" -> TransactionStatus.FAILED;
            case "03" -> TransactionStatus.REFUNDED;
            case "04" -> TransactionStatus.CANCELLED;
            case "05" -> TransactionStatus.EXPIRED;
            default -> TransactionStatus.UNKNOWN;
        };
    }

    // Accepts ISO-8601 with offset (2020-12-23T08:46:11+07:00), with Z, or without
    // a zone; falls back to now() so a malformed header never drops the callback.
    private LocalDateTime parseTimestamp(String xTimestamp) {
        if (xTimestamp == null || xTimestamp.isBlank()) return LocalDateTime.now();
        try {
            return OffsetDateTime.parse(xTimestamp).toLocalDateTime();
        } catch (DateTimeParseException withOffset) {
            try {
                return LocalDateTime.parse(xTimestamp);
            } catch (DateTimeParseException noZone) {
                log.warn("[CALLBACK] Unparseable X-TIMESTAMP '{}', using now()", xTimestamp);
                return LocalDateTime.now();
            }
        }
    }

    private String toJson(QrMpmNotifyRequestDto req) {
        try {
            return objectMapper.writeValueAsString(req);
        } catch (JsonProcessingException e) {
            log.warn("[CALLBACK] Failed to serialize payload for storage: {}", e.getMessage());
            return null;
        }
    }
}
