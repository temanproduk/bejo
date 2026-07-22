package com.bejo.commerce.repository;

import com.bejo.commerce.domain.StockMutationItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;

@Repository
public interface StockMutationItemRepository extends JpaRepository<StockMutationItem, Long> {
    List<StockMutationItem> findByMutationIdOrderByIdAsc(Long mutationId);

    // Batch-load items for several mutations at once (avoids N+1 in the history list)
    List<StockMutationItem> findByMutationIdInOrderByIdAsc(Collection<Long> mutationIds);
}
