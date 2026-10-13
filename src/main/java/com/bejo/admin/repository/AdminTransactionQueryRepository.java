package com.bejo.admin.repository;

import com.bejo.admin.dto.AdminTransactionRowDto;
import com.bejo.commerce.domain.Transaction;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

/**
 * The dashboard's cross-business transaction list, newest first, including soft-deleted rows (shown as
 * deleted). Read-only and not scoped by business. The business name is joined in by the soft
 * {@code business_id} reference (a left join: a transaction is never hidden by a missing business).
 *
 * <p>No parameter is ever null — see {@link AdminUserQueryRepository}.
 */
public interface AdminTransactionQueryRepository extends Repository<Transaction, Long> {

    String FILTER = " from Transaction t left join Business b on b.id = t.businessId"
            + " where t.transactionDate >= :from and t.transactionDate < :to"
            + " and (:type = '' or t.type = :type)"
            + " and (:status = '' or t.status = :status)"
            + " and (lower(t.transactionId) like :q escape '!' or lower(t.rrn) like :q escape '!'"
            + "   or lower(t.createdBy) like :q escape '!' or lower(b.name) like :q escape '!')";

    @Query(value = "select new com.bejo.admin.dto.AdminTransactionRowDto(t.transactionId, t.transactionDate,"
            + " t.amount, t.type, t.status, t.acquirer, t.rrn, t.businessId, b.name, t.createdBy, t.deletedAt)"
            + FILTER + " order by t.transactionDate desc, t.id desc",
            countQuery = "select count(t)" + FILTER)
    Page<AdminTransactionRowDto> search(@Param("q") String likePattern,
                                        @Param("from") LocalDateTime fromInclusive,
                                        @Param("to") LocalDateTime toExclusive,
                                        @Param("type") String type, @Param("status") String status,
                                        Pageable pageable);
}
