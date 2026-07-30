package com.bejo.commerce.service;
import com.bejo.notification.PushNotificationService;

import com.bejo.commerce.domain.Acquirer;
import com.bejo.commerce.domain.TransactionStatus;
import com.bejo.commerce.domain.TransactionType;
import com.bejo.commerce.dto.QrMpmNotifyRequestDto;
import com.bejo.commerce.dto.QrMpmNotifyResponseDto;
import com.bejo.core.domain.Business;
import com.bejo.commerce.domain.Transaction;
import com.bejo.core.domain.User;
import com.bejo.core.domain.UserQris;
import com.bejo.shared.exception.ResourceNotFoundException;
import com.bejo.notification.PushPayload;
import com.bejo.commerce.push.TransactionPushPayload;
import com.bejo.core.repository.BusinessRepository;
import com.bejo.commerce.repository.TransactionRepository;
import com.bejo.core.repository.UserQrisRepository;
import com.bejo.core.repository.UserRepository;
import com.bejo.shared.util.AppTime;
import com.bejo.shared.util.Rupiah;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class QrisCallbackService {

    private static final Logger log = LoggerFactory.getLogger(QrisCallbackService.class);

    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final BusinessRepository businessRepository;
    private final UserQrisRepository userQrisRepository;
    private final PushNotificationService pushNotificationService;
    private final ObjectMapper objectMapper;
    private final Counter callbackCounter;

    // Fallback account a callback is attributed to when its merchant PAN matches no business.
    @Value("${app.callback.default-username:admin}")
    private String defaultUsername;

    public QrisCallbackService(TransactionRepository transactionRepository,
                               UserRepository userRepository,
                               BusinessRepository businessRepository,
                               UserQrisRepository userQrisRepository,
                               PushNotificationService pushNotificationService,
                               ObjectMapper objectMapper,
                               MeterRegistry meterRegistry) {
        this.transactionRepository = transactionRepository;
        this.userRepository = userRepository;
        this.businessRepository = businessRepository;
        this.userQrisRepository = userQrisRepository;
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
        QrMpmNotifyRequestDto.AdditionalInfo info = req.additionalInfo();
        log.debug("[CALLBACK] Received status={} amount={} merchantPan={} terminalId={} providerTxnId={}",
                req.latestTransactionStatus(), req.amount() != null ? req.amount().value() : null,
                info != null ? info.merchantPan() : null, info != null ? info.terminalId() : null,
                info != null ? info.transactionId() : null);

        // Route by merchant PAN (the callback carries no NMID); fall back to the default account.
        Business business = resolveBusiness(info != null ? info.merchantPan() : null);
        Long businessId = business.getId();
        String businessName = business.getName();

        BigDecimal amount = parseAmount(req.amount() != null ? req.amount().value() : null);
        BigDecimal tip = info != null && info.tip() != null ? parseAmount(info.tip()) : BigDecimal.ZERO;
        String status = mapStatus(req.latestTransactionStatus());

        // Dedupe on the provider's own reference: payment gateways retry a notification on any
        // timeout or non-2xx, and without this every retry recorded the same payment again,
        // inflating the merchant's history and totals. Prefer additionalInfo.transactionId,
        // falling back to the SNAP originalReferenceNo.
        String providerReference = firstNonBlank(
                info != null ? info.transactionId() : null, req.originalReferenceNo());
        if (providerReference != null) {
            var duplicate = transactionRepository.findByIdempotencyKeyAndBusinessId(providerReference, businessId);
            if (duplicate.isPresent()) {
                log.info("[CALLBACK] Duplicate notification for providerRef={} businessId={} — already recorded as txnId={}",
                        providerReference, businessId, duplicate.get().getTransactionId());
                return response(duplicate.get().getId(), req, amount, tip);
            }
        } else {
            log.warn("[CALLBACK] Notification carries no provider reference — cannot dedupe, recording as new");
        }

        String transactionId = "TXN-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();

        Transaction t = new Transaction();
        t.setTransactionId(transactionId);
        // The provider's reference doubles as the idempotency key; the unique constraint on
        // it is the backstop when two retries arrive at the same moment.
        t.setIdempotencyKey(providerReference);
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
        log.info("[CALLBACK] Recorded txnId={} businessId={} amount={} status={} merchantPan={} providerTxnId={}",
                transactionId, businessId, t.getAmount(), status,
                info != null ? info.merchantPan() : null, info != null ? info.transactionId() : null);

        // Best-effort push to the terminal's recipients — must never fail the callback.
        try {
            String terminalId = info != null ? info.terminalId() : null;
            List<String> recipients = resolveRecipients(businessId, terminalId);
            log.debug("[CALLBACK] Routing push to {} recipient(s) for businessId={} terminalId={}",
                    recipients.size(), businessId, terminalId);
            pushNotificationService.sendToUsernames(recipients, buildPushData(t, businessName));
        } catch (RuntimeException e) {
            log.warn("[CALLBACK] Push step failed for transactionId={} (transaction still recorded): {}",
                    transactionId, e.getMessage());
        }

        return response(t.getId(), req, amount, tip);
    }

    /**
     * The provider's reference for this payment, used as the dedupe key. Returns null when the
     * payload carries neither field, in which case the notification can't be deduped.
     *
     * <p>A simultaneous re-delivery can still slip past the lookup and hit the unique constraint;
     * that surfaces as a 409, and the provider's next retry finds the committed row and gets a
     * normal 200 — so the duplicate still never becomes a second transaction.
     */
    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) return first;
        if (second != null && !second.isBlank()) return second;
        return null;
    }

    // The business a callback belongs to: matched by merchant PAN, else the configured default account.
    private Business resolveBusiness(String merchantPan) {
        if (merchantPan != null && !merchantPan.isBlank()) {
            Optional<Business> byPan = businessRepository.findFirstByMerchantPan(merchantPan);
            if (byPan.isPresent()) return byPan.get();
            log.warn("[CALLBACK] No business for merchantPan={} — falling back to default account", merchantPan);
        }
        User fallback = userRepository.findByUsernameAndDeletedAtIsNull(defaultUsername)
                .orElseThrow(() -> {
                    log.error("[CALLBACK] Default account '{}' not found — payment NOT recorded", defaultUsername);
                    return new ResourceNotFoundException("Default account '" + defaultUsername + "' not found");
                });
        return businessRepository.findById(fallback.getBusinessId())
                .orElseThrow(() -> new ResourceNotFoundException("Business not found for default account"));
    }

    /**
     * Which users' devices get the payment push, by QRIS terminal:
     * <ul>
     *   <li>the <b>super admin</b> (main account) — always;</li>
     *   <li>a member with <b>no</b> per-user QRIS — always (they use the shared business QRIS);</li>
     *   <li>a member with a per-user QRIS — only when its terminal id matches the callback's.</li>
     * </ul>
     * If the callback carries no terminal id, everyone in the business is notified (can't route).
     */
    private List<String> resolveRecipients(Long businessId, String terminalId) {
        List<User> users = userRepository.findByBusinessIdAndDeletedAtIsNullOrderByCreatedAtAsc(businessId);
        if (terminalId == null || terminalId.isBlank()) {
            return users.stream().map(User::getUsername).toList();
        }
        Map<String, String> userTerminal = new HashMap<>();
        for (UserQris q : userQrisRepository.findByBusinessId(businessId)) {
            userTerminal.put(q.getUsername(), q.getTerminalId());
        }
        List<String> recipients = new ArrayList<>();
        for (User u : users) {
            String ownTerminal = userTerminal.get(u.getUsername());
            if (u.isSuperAdmin() || ownTerminal == null || ownTerminal.equals(terminalId)) {
                recipients.add(u.getUsername());
            }
        }
        return recipients;
    }

    private PushPayload buildPushData(Transaction t, String merchantName) {
        // A payment push (PushType.TRANSACTION), not an attendance event; the app renders it.
        return new TransactionPushPayload(t, merchantName,
                "Pembayaran Diterima", "Pembayaran " + Rupiah.format(t.getAmount()) + " diterima");
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
            log.warn("[CALLBACK] Invalid amount value '{}', using 0", value);
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
