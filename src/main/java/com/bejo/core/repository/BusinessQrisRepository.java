package com.bejo.core.repository;

import com.bejo.core.domain.BusinessQris;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BusinessQrisRepository extends JpaRepository<BusinessQris, Long> {
    Optional<BusinessQris> findByBusinessId(Long businessId);
}
