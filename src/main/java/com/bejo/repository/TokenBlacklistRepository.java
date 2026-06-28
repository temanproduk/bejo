package com.bejo.repository;

import com.bejo.entity.TokenBlacklist;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;

public interface TokenBlacklistRepository extends JpaRepository<TokenBlacklist, String> {

    boolean existsByJti(String jti);

    void deleteAllByExpiresAtBefore(LocalDateTime cutoff);
}
