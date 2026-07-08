package com.bejo.repository;

import com.bejo.entity.Purchase;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PurchaseRepository extends JpaRepository<Purchase, Long> {
    List<Purchase> findByUsernameOrderByCreatedAtDesc(String username);
    Optional<Purchase> findByIdAndUsername(Long id, String username);
}
