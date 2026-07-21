package com.bejo.service;

import com.bejo.constant.Acquirer;
import com.bejo.constant.TransactionStatus;
import com.bejo.constant.TransactionType;
import com.bejo.dto.request.QrMpmNotifyRequestDto;
import com.bejo.dto.response.QrMpmNotifyResponseDto;
import com.bejo.entity.Business;
import com.bejo.entity.Transaction;
import com.bejo.entity.User;
import com.bejo.exception.ResourceNotFoundException;
import com.bejo.push.PushPayload;
import com.bejo.push.TransactionPushPayload;
import com.bejo.repository.BusinessRepository;
import com.bejo.repository.TransactionRepository;
import com.bejo.repository.UserRepository;
import com.bejo.util.AppTime;
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
import java.util.UUID;

@Service
public class QrisCallbackService {

    private static final Logger log = LoggerFactory.getLogger(QrisCallbackService.class);

    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final BusinessRepository businessRepository;
    private final PushNotificationService pushNotificationService;
    private final ObjectMapper objectMapper;
    private final Counter callbackCounter;

    // The seed/merchant account every callback is attributed to (default: seed user).
    @Value("${app.callback.default-username:admin}")
    private String defaultUsername;

    public QrisCallbackService(TransactionRepository transactionRepository,
                               UserRepository userRepository,
                               BusinessRepository businessRepository,
                               PushNotificationService pushNotificationService,
                               ObjectMapper objectMapper,
                               MeterRegistry meterRegistry) {
        this.transactionRepository = transactionRepository;
        this.userRepository = userRepository;
        this.businessRepository = businessRepository;
        this.pushNotificationService = pushNotificationService;
        this.objectMapper = objectMapper;
        this.callbackCounter = Counter.builder("qris.callbacks.received").register(meterRegistry);
    }

    /**
     * PoC behavior: the provider's callback is currently buggy — it only sends a valid
     * amount + status, with merchantPan / reference numbers / transactionId all null.
     * So we can't route by NMID or dedupe by reference. For now, EVERY callback records
     * a MANAGED transaction against the seed user and pushes a notification to its
     * devices. Restore NMID routing + reference-based idempotency once the provider
     * fixes the payload.
     */
    @Transactional
    public QrMpmNotifyResponseDto processNotify(QrMpmNotifyRequestDto req, String xTimestamp) {
        callbackCounter.increment();
        log.info("[CALLBACK] status={} amount={}",
                req.latestTransactionStatus(), req.amount() != null ? req.amount().value() : null);

        User seedUser = userRepository.findByUsernameAndDeletedAtIsNull(defaultUsername)
                .orElseThrow(() -> {
                    log.error("[CALLBACK] Seed user '{}' not found — payment NOT recorded", defaultUsername);
                    return new ResourceNotFoundException("Seed user '" + defaultUsername + "' not found");
                });
        Long businessId = seedUser.getBusinessId();
        String businessName = businessRepository.findById(businessId).map(Business::getName).orElse(null);

        QrMpmNotifyRequestDto.AdditionalInfo info = req.additionalInfo();
        BigDecimal amount = parseAmount(req.amount() != null ? req.amount().value() : null);
        BigDecimal tip = info != null && info.tip() != null ? parseAmount(info.tip()) : BigDecimal.ZERO;
        String status = mapStatus(req.latestTransactionStatus());
        String transactionId = "TXN-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();

        Transaction t = new Transaction();
        t.setTransactionId(transactionId);
        // PoC: no reference number to dedupe on, so no idempotency key — each callback
        // creates a distinct transaction (Postgres allows multiple NULL unique values).
        t.setIdempotencyKey(null);
        t.setInvoiceId(req.originalReferenceNo());
        //t.setTransactionDate(parseTimestamp(xTimestamp));
        t.setTransactionDate(LocalDateTime.now());
        t.setAmount(amount.setScale(0, RoundingMode.HALF_UP).longValueExact());
        t.setStatus(status);
        t.setBusinessId(businessId);
        t.setType(TransactionType.MANAGED);
        t.setAcquirer(info != null && info.deviceId() != null ? info.deviceId() : Acquirer.ASTRAPAY);
        // Issuer is hard-coded: every QRIS callback comes from AstraPay, and the provider's
        // payload doesn't reliably carry issuerName (PoC mode).
        t.setSource(Acquirer.ASTRAPAY);
        t.setMerchantPan(info != null ? info.merchantPan() : null);
        t.setCustomerPan(info != null && info.customerPan() != null ? info.customerPan() : req.customerNumber());
        t.setCustomerName(req.destinationAccountName());
        t.setScrapeDate(LocalDateTime.now());
        t.setInfo(toJson(req));
        transactionRepository.save(t);
        log.info("[CALLBACK] Recorded transactionId={} businessId={} amount={} status={}",
                transactionId, businessId, t.getAmount(), status);

        // Best-effort push to EVERY device of EVERY user in the business — must never fail the callback
        try {
            pushNotificationService.sendToBusiness(businessId, buildPushData(t, businessName));
        } catch (RuntimeException e) {
            log.error("[CALLBACK] Push step failed for transactionId={} (transaction still recorded): {}",
                    transactionId, e.getMessage(), e);
        }

        return response(t.getId(), req, amount, tip);
    }

    private PushPayload buildPushData(Transaction t, String merchantName) {
        // A payment push (PushType.TRANSACTION), not an attendance event; the app renders it.
        return new TransactionPushPayload(t, merchantName,
                "Pembayaran Diterima", "Pembayaran Rp" + t.getAmount() + " diterima");
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

    // Null/blank/invalid amount degrades to 0 so a buggy payload never drops the callback.
    private BigDecimal parseAmount(String value) {
        if (value == null || value.isBlank()) return BigDecimal.ZERO;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            log.error("[CALLBACK] Invalid amount value '{}', using 0", value);
            return BigDecimal.ZERO;
        }
    }

    // 00=SUCCESS, 01=PENDING, 02=FAILED, 03=REFUNDED, 04=CANCELLED, 05=EXPIRED
    private String mapStatus(String code) {
        if (code == null) return TransactionStatus.UNKNOWN;
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
    // a zone; falls back to now() so a missing/malformed header never drops the callback.
    // When an offset is present it's normalized to WIB wall-clock (so a `Z`/UTC stamp is
    // shifted +7), keeping stored transaction_date consistent with the app timezone.
    private LocalDateTime parseTimestamp(String xTimestamp) {
        if (xTimestamp == null || xTimestamp.isBlank()) return LocalDateTime.now();
        try {
            return OffsetDateTime.parse(xTimestamp).atZoneSameInstant(AppTime.ZONE).toLocalDateTime();
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
