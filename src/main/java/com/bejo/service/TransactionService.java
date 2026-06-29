package com.bejo.service;

import com.bejo.dto.request.AddTransactionRequest;
import com.bejo.dto.response.TransactionCreatedData;
import com.bejo.dto.response.TransactionDto;
import com.bejo.entity.Transaction;
import com.bejo.exception.ResourceNotFoundException;
import com.bejo.repository.TransactionRepository;
import com.bejo.repository.UserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    private final TransactionRepository transactionRepository;
    private final UserRepository userRepository;
    private final Counter transactionFetchCounter;

    public TransactionService(TransactionRepository transactionRepository,
                              UserRepository userRepository,
                              MeterRegistry meterRegistry) {
        this.transactionRepository = transactionRepository;
        this.userRepository = userRepository;
        this.transactionFetchCounter = Counter.builder("transactions.fetched").register(meterRegistry);
    }

    public List<TransactionDto> getHistory(String username, LocalDate dateStart, LocalDate dateEnd) {
        log.info("[HISTORY] username={} dateStart={} dateEnd={}", username, dateStart, dateEnd);

        String merchantName = userRepository.findByUsername(username)
                .map(u -> u.getBusinessName())
                .orElse(null);

        LocalDateTime start = dateStart != null ? dateStart.atStartOfDay() : null;
        LocalDateTime end   = dateEnd   != null ? dateEnd.atTime(23, 59, 59) : null;

        List<Transaction> txns;
        if (start == null && end == null) {
            txns = transactionRepository.findByUsernameOrderByTransactionDateDesc(username);
        } else if (start != null && end != null) {
            txns = transactionRepository.findByUsernameAndTransactionDateBetweenOrderByTransactionDateDesc(username, start, end);
        } else if (start != null) {
            txns = transactionRepository.findByUsernameAndTransactionDateGreaterThanEqualOrderByTransactionDateDesc(username, start);
        } else {
            txns = transactionRepository.findByUsernameAndTransactionDateLessThanEqualOrderByTransactionDateDesc(username, end);
        }

        List<TransactionDto> result = txns.stream()
                .map(t -> new TransactionDto(
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
                ))
                .toList();

        transactionFetchCounter.increment(result.size());
        log.info("[HISTORY] Returning {} transactions for username={}", result.size(), username);
        return result;
    }

    @Transactional
    public TransactionCreatedData create(String username, String idempotencyKey, AddTransactionRequest request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header wajib diisi");
        }

        // Idempotency check — return existing transactionId if key was already processed
        var existing = transactionRepository.findByIdempotencyKeyAndUsername(idempotencyKey, username);
        if (existing.isPresent()) {
            log.info("[TXN] Idempotent replay key={} username={} transactionId={}", idempotencyKey, username, existing.get().getTransactionId());
            return new TransactionCreatedData(existing.get().getTransactionId());
        }

        userRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        boolean isParsed = "PARSED".equals(request.type());
        if (isParsed && (request.acquirer() == null || request.acquirer().isBlank())) {
            throw new IllegalArgumentException("acquirer wajib diisi untuk type PARSED");
        }

        String acquirer = isParsed ? request.acquirer() : "Cash";
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
        t.setStatus("SUCCESS");
        t.setScrapeDate(LocalDateTime.now());
        transactionRepository.save(t);
        log.info("[TXN] Created type={} acquirer={} transactionId={}", request.type(), acquirer, transactionId);
        return new TransactionCreatedData(transactionId);
    }
}
