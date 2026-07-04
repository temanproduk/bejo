package com.bejo.repository;

import com.bejo.entity.UserAppSettings;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserAppSettingsRepository extends JpaRepository<UserAppSettings, Long> {
    Optional<UserAppSettings> findByUsername(String username);
}
