package com.bejo.repository;

import com.bejo.entity.StockMutationItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface StockMutationItemRepository extends JpaRepository<StockMutationItem, Long> {
    List<StockMutationItem> findByMutationIdOrderByIdAsc(Long mutationId);
}
