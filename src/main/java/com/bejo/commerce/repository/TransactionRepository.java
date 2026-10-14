package com.bejo.commerce.repository;

import com.bejo.commerce.domain.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, Long> {

    // Sum + count of a business's active transactions in [start, end) — for the summary endpoint.
    // Transactions that are not income — still awaiting payment, or cancelled — are left out via `excludedStatuses`.
    @Query("""
            SELECT COALESCE(SUM(t.amount), 0) AS totalAmount, COUNT(t) AS count
            FROM Transaction t
            WHERE t.businessId = :businessId AND t.deletedAt IS NULL
              AND (t.status IS NULL OR t.status NOT IN :excludedStatuses)
              AND t.transactionDate >= :start AND t.transactionDate < :end
            """)
    TransactionSummaryProjection sumBetween(@Param("businessId") Long businessId,
                                            @Param("start") LocalDateTime start,
                                            @Param("end") LocalDateTime end,
                                            @Param("excludedStatuses") Collection<String> excludedStatuses);

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

    // The transaction a PARSED payment settled, found by that payment's Idempotency-Key (a retried request).
    // Includes soft-deleted rows for the same reason as the lookup above: a replay must not settle another.
    Optional<Transaction> findByPaymentReferenceAndBusinessId(String paymentReference, Long businessId);

    // The oldest transaction in `status` for exactly `amount` — the one an incoming payment settles first
    Optional<Transaction> findFirstByBusinessIdAndDeletedAtIsNullAndStatusAndAmountOrderByTransactionDateAscIdAsc(
            Long businessId, String status, Long amount);

    // Ids of active transactions in `status` dated before `cutoff`, oldest first — the day-end sweep's work list
    @Query("""
            select t.id from Transaction t
             where t.status = :status and t.deletedAt is null and t.transactionDate < :cutoff
             order by t.id
            """)
    List<Long> findIdsByStatusAndTransactionDateBefore(@Param("status") String status,
                                                       @Param("cutoff") LocalDateTime cutoff, Pageable limit);

    /**
     * Cancels one awaiting-payment transaction. Conditional on it still waiting, like {@link #settleAwaiting},
     * so a payment that settles it a moment earlier wins and this sees 0 rows.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Transaction t set t.status = :cancelledStatus
             where t.id = :id and t.deletedAt is null and t.status = :awaitingStatus
            """)
    int cancelAwaiting(@Param("id") Long id, @Param("awaitingStatus") String awaitingStatus,
                       @Param("cancelledStatus") String cancelledStatus);

    /**
     * Settles one awaiting-payment transaction: a single conditional statement, so two payments (or a
     * payment and a tap on "Sudah Terbayar") racing for the same row cannot both win — the loser sees 0 rows
     * and moves on. The same idea as the stock updates in ProductRepository. {@code clearAutomatically}
     * because the loaded entity is stale afterwards; callers re-read the row.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("""
            update Transaction t
               set t.status = :paidStatus, t.paidAt = :paidAt, t.paymentReference = :paymentReference
             where t.id = :id and t.businessId = :businessId and t.deletedAt is null
               and t.status = :awaitingStatus
            """)
    int settleAwaiting(@Param("id") Long id, @Param("businessId") Long businessId,
                       @Param("awaitingStatus") String awaitingStatus, @Param("paidStatus") String paidStatus,
                       @Param("paidAt") LocalDateTime paidAt, @Param("paymentReference") String paymentReference);
}
