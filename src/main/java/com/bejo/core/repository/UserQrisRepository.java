package com.bejo.core.repository;

import com.bejo.core.domain.UserQris;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserQrisRepository extends JpaRepository<UserQris, Long> {

    Optional<UserQris> findByBusinessIdAndUsername(Long businessId, String username);

    // Whether this user has their own QRIS assigned (vs. falling back to the shared business
    // one) — surfaced on the login / me bundles so the app knows it detects its own payments.
    boolean existsByBusinessIdAndUsername(Long businessId, String username);

    // All per-user QRIS in a business — used by the main account's listing and by callback routing
    // (map username -> terminal id to decide who gets a payment push).
    List<UserQris> findByBusinessId(Long businessId);

    void deleteByBusinessIdAndUsername(Long businessId, String username);
}
