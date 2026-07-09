package com.bejo.repository;

import com.bejo.entity.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    Page<Transaction> findByUsernameAndDeletedAtIsNullOrderByTransactionDateDesc(String username, Pageable pageable);

    Page<Transaction> findByUsernameAndDeletedAtIsNullAndTransactionDateGreaterThanEqualOrderByTransactionDateDesc(
            String username, LocalDateTime start, Pageable pageable);

    Page<Transaction> findByUsernameAndDeletedAtIsNullAndTransactionDateLessThanEqualOrderByTransactionDateDesc(
            String username, LocalDateTime end, Pageable pageable);

    Page<Transaction> findByUsernameAndDeletedAtIsNullAndTransactionDateBetweenOrderByTransactionDateDesc(
            String username, LocalDateTime start, LocalDateTime end, Pageable pageable);

    // Deliberately includes soft-deleted rows for the active user record check
    boolean existsByUsername(String username);

    // Deliberately includes soft-deleted rows: the DB unique constraint on
    // transaction_id spans all rows, so a deleted id still can't be reused
    boolean existsByTransactionId(String transactionId);

    // Deliberately includes soft-deleted rows: replaying a create after deleting
    // the result must not attempt a second insert with the same unique key
    Optional<Transaction> findByIdempotencyKeyAndUsername(String idempotencyKey, String username);

    Optional<Transaction> findByTransactionIdAndUsernameAndDeletedAtIsNull(String transactionId, String username);
}
