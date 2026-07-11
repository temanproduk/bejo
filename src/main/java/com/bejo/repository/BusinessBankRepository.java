package com.bejo.repository;

import com.bejo.entity.BusinessBank;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BusinessBankRepository extends JpaRepository<BusinessBank, Long> {
    Optional<BusinessBank> findByBusinessId(Long businessId);
}
