package com.bejo.repository;

import com.bejo.entity.BusinessAppSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface BusinessAppSettingsRepository extends JpaRepository<BusinessAppSettings, Long> {
    Optional<BusinessAppSettings> findByBusinessId(Long businessId);
}
