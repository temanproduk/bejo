package com.bejo.service;

import com.bejo.constant.Acquirer;
import com.bejo.constant.TransactionStatus;
import com.bejo.constant.TransactionType;
import com.bejo.dto.request.AddTransactionRequestDto;
import com.bejo.dto.request.BulkAddTransactionRequestDto;
import com.bejo.dto.request.BulkTransactionItemDto;
import com.bejo.dto.request.UpdateTransactionRequestDto;
import com.bejo.dto.response.BulkTransactionResultResponseDto;
import com.bejo.dto.response.TransactionCreatedResponseDto;
import com.bejo.dto.response.TransactionResponseDto;
import com.bejo.entity.Transaction;
import com.bejo.exception.ResourceNotFoundException;
import com.bejo.repository.TransactionRepository;
import com.bejo.repository.UserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    // Bulk-sync per-item result statuses
    private static final String RESULT_CREATED = "CREATED";
    private static final String RESULT_DUPLICATE = "DUPLICATE";
    private static final String RESULT_FAILED = "FAILED";

    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final Validator validator;
    private final Counter transactionFetchCounter;

    public TransactionService(TransactionRepository transactionRepository,
                              UserRepository userRepository,
                              Validator validator,
                              MeterRegistry meterRegistry) {
        this.transactionRepository = transactionRepository;
        this.userRepository = userRepository;
        this.validator = validator;
        this.transactionFetchCounter = Counter.builder("transactions.fetched").register(meterRegistry);
    }

    public Page<TransactionResponseDto> getHistory(String username, LocalDate dateStart, LocalDate dateEnd, Pageable pageable) {
        log.info("[HISTORY] username={} dateStart={} dateEnd={} page={} size={}",
                username, dateStart, dateEnd, pageable.getPageNumber(), pageable.getPageSize());

        String merchantName = userRepository.findByUsername(username)
                .map(u -> u.getBusinessName())
                .orElse(null);

        LocalDateTime start = dateStart != null ? dateStart.atStartOfDay() : null;
        LocalDateTime end   = dateEnd   != null ? dateEnd.atTime(java.time.LocalTime.MAX) : null;

        Page<Transaction> txns;
        if (start == null && end == null) {
            txns = transactionRepository.findByUsernameAndDeletedAtIsNullOrderByTransactionDateDesc(username, pageable);
        } else if (start != null && end != null) {
            txns = transactionRepository.findByUsernameAndDeletedAtIsNullAndTransactionDateBetweenOrderByTransactionDateDesc(username, start, end, pageable);
        } else if (start != null) {
            txns = transactionRepository.findByUsernameAndDeletedAtIsNullAndTransactionDateGreaterThanEqualOrderByTransactionDateDesc(username, start, pageable);
        } else {
            txns = transactionRepository.findByUsernameAndDeletedAtIsNullAndTransactionDateLessThanEqualOrderByTransactionDateDesc(username, end, pageable);
        }

        Page<TransactionResponseDto> result = txns.map(t -> toDto(t, merchantName));

        transactionFetchCounter.increment(result.getNumberOfElements());
        log.info("[HISTORY] Returning {} of {} transactions (page {}/{}) for username={}",
                result.getNumberOfElements(), result.getTotalElements(),
                result.getNumber() + 1, result.getTotalPages(), username);
        return result;
    }

    @Transactional
    public TransactionCreatedResponseDto create(String username, String idempotencyKey, AddTransactionRequestDto request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header wajib diisi");
        }

        // Idempotency check — return existing transactionId if key was already processed
        var existing = transactionRepository.findByIdempotencyKeyAndUsername(idempotencyKey, username);
        if (existing.isPresent()) {
            log.info("[TXN] Idempotent replay key={} username={} transactionId={}", idempotencyKey, username, existing.get().getTransactionId());
            return new TransactionCreatedResponseDto(existing.get().getTransactionId());
        }

        userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        Transaction t = buildTransaction(username, idempotencyKey, request);
        transactionRepository.save(t);
        log.info("[TXN] Created type={} acquirer={} transactionId={}", t.getType(), t.getAcquirer(), t.getTransactionId());
        return new TransactionCreatedResponseDto(t.getTransactionId());
    }

    /**
     * Bulk-create manually-entered transactions synced from the client after offline
     * entry. Each item is processed independently (its own DB transaction), so a
     * malformed or duplicate row never blocks the rest of the batch:
     * <ul>
     *   <li>invalid item → {@code FAILED} with the validation message;</li>
     *   <li>{@code idempotencyKey} already stored (a prior partial sync) → {@code DUPLICATE}
     *       with the existing transaction id — safe to re-send;</li>
     *   <li>otherwise → {@code CREATED} with the new transaction id.</li>
     * </ul>
     * Not {@code @Transactional} on purpose — each save commits on its own so partial
     * progress survives, and a per-item constraint violation can't poison the batch.
     */
    public BulkTransactionResultResponseDto bulkCreate(String username, BulkAddTransactionRequestDto request) {
        List<BulkTransactionItemDto> items = request.transactions();
        log.info("[TXN-BULK] username={} items={}", username, items.size());
        userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        List<BulkTransactionResultResponseDto.ItemResult> results = new ArrayList<>();
        int created = 0, duplicate = 0, failed = 0;

        for (BulkTransactionItemDto item : items) {
            String key = item.idempotencyKey();

            // Per-item validation so one bad row fails alone instead of the whole batch
            Set<ConstraintViolation<BulkTransactionItemDto>> violations = validator.validate(item);
            if (!violations.isEmpty()) {
                failed++;
                results.add(failedResult(key, violations.stream()
                        .map(ConstraintViolation::getMessage)
                        .collect(Collectors.joining(", "))));
                continue;
            }

            try {
                var existing = transactionRepository.findByIdempotencyKeyAndUsername(key, username);
                if (existing.isPresent()) {
                    duplicate++;
                    results.add(new BulkTransactionResultResponseDto.ItemResult(
                            key, RESULT_DUPLICATE, existing.get().getTransactionId(), null));
                    continue;
                }
                Transaction t = buildTransaction(username, key, item.transaction());
                transactionRepository.save(t);
                created++;
                results.add(new BulkTransactionResultResponseDto.ItemResult(
                        key, RESULT_CREATED, t.getTransactionId(), null));
            } catch (DataIntegrityViolationException e) {
                // Concurrent insert with the same idempotency key — treat as already synced
                duplicate++;
                String existingId = transactionRepository.findByIdempotencyKeyAndUsername(key, username)
                        .map(Transaction::getTransactionId).orElse(null);
                results.add(new BulkTransactionResultResponseDto.ItemResult(
                        key, RESULT_DUPLICATE, existingId, null));
            } catch (IllegalArgumentException e) {
                failed++;
                results.add(failedResult(key, e.getMessage()));
            }
        }

        log.info("[TXN-BULK] Done username={} created={} duplicate={} failed={}",
                username, created, duplicate, failed);
        return new BulkTransactionResultResponseDto(items.size(), created, duplicate, failed, results);
    }

    private BulkTransactionResultResponseDto.ItemResult failedResult(String key, String error) {
        return new BulkTransactionResultResponseDto.ItemResult(key, RESULT_FAILED, null, error);
    }

    // Builds an unsaved MANUAL/PARSED transaction, applying the cross-field rule that
    // PARSED requires an explicit acquirer (MANUAL defaults to Cash).
    private Transaction buildTransaction(String username, String idempotencyKey, AddTransactionRequestDto request) {
        boolean isParsed = TransactionType.PARSED.equals(request.type());
        if (isParsed && (request.acquirer() == null || request.acquirer().isBlank())) {
            throw new IllegalArgumentException("acquirer wajib diisi untuk type PARSED");
        }
        String acquirer = isParsed ? request.acquirer() : Acquirer.CASH;
        String transactionId = "TXN-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();

        Transaction t = new Transaction();
        t.setTransactionId(transactionId);
        t.setIdempotencyKey(idempotencyKey);
        t.setTransactionDate(request.transactionDate());
        t.setAmount(request.amount());
        t.setSource(request.issuerName());
        t.setUsername(username);
        t.setMerchantPan(request.merchantPan());
        t.setCustomerPan(request.customerPan());
        t.setRrn(request.rrn());
        t.setType(request.type());
        t.setAcquirer(acquirer);
        t.setStatus(TransactionStatus.SUCCESS);
        t.setScrapeDate(LocalDateTime.now());
        return t;
    }

    @Transactional
    public TransactionResponseDto update(String username, String transactionId, UpdateTransactionRequestDto request) {
        if (request.isEmpty()) {
            throw new IllegalArgumentException("Tidak ada field yang diubah");
        }

        Transaction t = transactionRepository.findByTransactionIdAndUsernameAndDeletedAtIsNull(transactionId, username)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + transactionId));

        // MANAGED rows are pushed by the Bejo server and are the payment record
        // of truth — only MANUAL/PARSED entries are user-editable
        if (!TransactionType.MANUAL.equals(t.getType()) && !TransactionType.PARSED.equals(t.getType())) {
            throw new IllegalArgumentException("Transaksi bertipe " + t.getType() + " tidak dapat diubah");
        }

        if (request.acquirer() != null && request.acquirer().isBlank()) {
            throw new IllegalArgumentException("acquirer tidak boleh kosong");
        }

        if (request.transactionDate() != null) t.setTransactionDate(request.transactionDate());
        if (request.amount() != null) t.setAmount(request.amount());
        if (request.issuerName() != null) t.setSource(request.issuerName());
        if (request.merchantPan() != null) t.setMerchantPan(request.merchantPan());
        if (request.customerPan() != null) t.setCustomerPan(request.customerPan());
        if (request.rrn() != null) t.setRrn(request.rrn());
        if (request.acquirer() != null) t.setAcquirer(request.acquirer());
        transactionRepository.save(t);
        log.info("[TXN] Updated transactionId={} username={}", transactionId, username);

        String merchantName = userRepository.findByUsername(username)
                .map(u -> u.getBusinessName())
                .orElse(null);
        return toDto(t, merchantName);
    }

    @Transactional
    public void delete(String username, String transactionId) {
        Transaction t = transactionRepository.findByTransactionIdAndUsernameAndDeletedAtIsNull(transactionId, username)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + transactionId));
        t.setDeletedAt(LocalDateTime.now());
        transactionRepository.save(t);
        log.info("[TXN] Soft-deleted transactionId={} username={}", transactionId, username);
    }

    private TransactionResponseDto toDto(Transaction t, String merchantName) {
        return new TransactionResponseDto(
                t.getTransactionId(),
                t.getTransactionDate(),
                t.getAmount(),
                t.getType(),
                t.getAcquirer(),
                t.getSource(),
                merchantName,
                t.getMerchantPan(),
                t.getCustomerPan(),
                t.getRrn()
        );
    }
}
