package com.bejo.commerce.repository;

import com.bejo.commerce.domain.TransactionItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TransactionItemRepository extends JpaRepository<TransactionItem, Long> {
    List<TransactionItem> findByTransactionIdOrderByIdAsc(Long transactionId);
}
