package com.bejo.core.repository;

import com.bejo.core.domain.Business;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BusinessRepository extends JpaRepository<Business, Long> {

    // The QRIS callback carries a merchant PAN, so this routes a payment to its business.
    // findFirst (not findBy) so a duplicate in the data never throws.
    Optional<Business> findFirstByMerchantPan(String merchantPan);
}
