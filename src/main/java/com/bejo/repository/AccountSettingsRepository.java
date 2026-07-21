package com.bejo.repository;

import com.bejo.entity.AccountSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface AccountSettingsRepository extends JpaRepository<AccountSettings, Long> {
    Optional<AccountSettings> findByBusinessId(Long businessId);
}
