package com.bejo.repository;

import com.bejo.entity.UserQris;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserQrisRepository extends JpaRepository<UserQris, Long> {

    Optional<UserQris> findByBusinessIdAndUsername(Long businessId, String username);

    // All per-user QRIS in a business — used by the main account's listing and by callback routing
    // (map username -> terminal id to decide who gets a payment push).
    List<UserQris> findByBusinessId(Long businessId);

    void deleteByBusinessIdAndUsername(Long businessId, String username);
}
