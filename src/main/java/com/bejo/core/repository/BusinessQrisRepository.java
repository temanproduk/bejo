package com.bejo.core.repository;

import com.bejo.core.domain.BusinessQris;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BusinessQrisRepository extends JpaRepository<BusinessQris, Long> {
    Optional<BusinessQris> findByBusinessId(Long businessId);

    // Whether the business has a QRIS at all — registration creates no row, so this is false
    // until the first upload (see QrisService.hasOwnQris).
    boolean existsByBusinessId(Long businessId);
}
