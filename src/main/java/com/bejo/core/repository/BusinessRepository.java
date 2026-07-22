package com.bejo.core.repository;

import com.bejo.core.domain.Business;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BusinessRepository extends JpaRepository<Business, Long> {

    // findFirst (not findBy) so a duplicate NMID in the data never throws —
    // routing a callback should degrade gracefully, not 500
    Optional<Business> findFirstByNmid(String nmid);

    // The QRIS callback carries a merchant PAN (no NMID), so this routes a payment to its business.
    Optional<Business> findFirstByMerchantPan(String merchantPan);
}
