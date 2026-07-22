package com.bejo.commerce.repository;

import com.bejo.commerce.domain.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    // Sum + count of a business's active transactions in [start, end) — for the summary endpoint
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0) AS totalAmount, COUNT(t) AS count
            FROM Transaction t
            WHERE t.businessId = :businessId AND t.deletedAt IS NULL
              AND t.transactionDate >= :start AND t.transactionDate < :end
            """)
    TransactionSummaryProjection sumBetween(@Param("businessId") Long businessId,
                                            @Param("start") LocalDateTime start,
                                            @Param("end") LocalDateTime end);

    Page<Transaction> findByBusinessIdAndDeletedAtIsNullOrderByTransactionDateDesc(Long businessId, Pageable pageable);

    Page<Transaction> findByBusinessIdAndDeletedAtIsNullAndTransactionDateGreaterThanEqualOrderByTransactionDateDesc(
            Long businessId, LocalDateTime start, Pageable pageable);

    Page<Transaction> findByBusinessIdAndDeletedAtIsNullAndTransactionDateLessThanEqualOrderByTransactionDateDesc(
            Long businessId, LocalDateTime end, Pageable pageable);

    Page<Transaction> findByBusinessIdAndDeletedAtIsNullAndTransactionDateBetweenOrderByTransactionDateDesc(
            Long businessId, LocalDateTime start, LocalDateTime end, Pageable pageable);

    // Deliberately includes soft-deleted rows for the seeded-data check
    boolean existsByBusinessId(Long businessId);

    // Deliberately includes soft-deleted rows: the DB unique constraint on
    // transaction_id spans all rows, so a deleted id still can't be reused
    boolean existsByTransactionId(String transactionId);

    // Deliberately includes soft-deleted rows: replaying a create after deleting
    // the result must not attempt a second insert with the same unique key
    Optional<Transaction> findByIdempotencyKeyAndBusinessId(String idempotencyKey, Long businessId);

    Optional<Transaction> findByTransactionIdAndBusinessIdAndDeletedAtIsNull(String transactionId, Long businessId);
}
