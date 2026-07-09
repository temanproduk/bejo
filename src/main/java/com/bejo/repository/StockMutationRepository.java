package com.bejo.repository;

import com.bejo.entity.StockMutation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface StockMutationRepository extends JpaRepository<StockMutation, Long> {
    List<StockMutation> findByUsernameOrderByCreatedAtDesc(String username);
    Optional<StockMutation> findByIdAndUsername(Long id, String username);
}
