package com.bejo.repository;

import com.bejo.entity.StockMutation;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface StockMutationRepository extends JpaRepository<StockMutation, Long> {
    Page<StockMutation> findByBusinessIdOrderByCreatedAtDesc(Long businessId, Pageable pageable);
    Optional<StockMutation> findByIdAndBusinessId(Long id, Long businessId);
}
