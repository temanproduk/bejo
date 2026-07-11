package com.bejo.repository;

import com.bejo.entity.BusinessQris;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BusinessQrisRepository extends JpaRepository<BusinessQris, Long> {
    Optional<BusinessQris> findByBusinessId(Long businessId);
}
