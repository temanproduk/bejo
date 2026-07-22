package com.bejo.core.repository;

import com.bejo.core.domain.TokenBlacklist;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDateTime;

public interface TokenBlacklistRepository extends JpaRepository<TokenBlacklist, String> {

    boolean existsByJti(String jti);

    void deleteAllByExpiresAtBefore(LocalDateTime cutoff);
}
