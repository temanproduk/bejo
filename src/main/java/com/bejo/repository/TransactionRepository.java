package com.bejo.repository;

import com.bejo.entity.Transaction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    List<Transaction> findByUsernameOrderByTransactionDateDesc(String username);

    List<Transaction> findByUsernameAndTransactionDateGreaterThanEqualOrderByTransactionDateDesc(
            String username, LocalDateTime start);

    List<Transaction> findByUsernameAndTransactionDateLessThanEqualOrderByTransactionDateDesc(
            String username, LocalDateTime end);

    List<Transaction> findByUsernameAndTransactionDateBetweenOrderByTransactionDateDesc(
            String username, LocalDateTime start, LocalDateTime end);

    boolean existsByUsername(String username);

    boolean existsByTransactionId(String transactionId);

    Optional<Transaction> findByIdempotencyKeyAndUsername(String idempotencyKey, String username);
}
