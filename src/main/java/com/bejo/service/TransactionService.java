package com.bejo.service;

import com.bejo.dto.response.TransactionDto;
import com.bejo.entity.Transaction;
import com.bejo.repository.TransactionRepository;
import com.bejo.repository.UserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;

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
}
