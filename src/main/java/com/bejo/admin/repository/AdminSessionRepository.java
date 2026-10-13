package com.bejo.admin.repository;

import com.bejo.admin.domain.AdminSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

public interface AdminSessionRepository extends JpaRepository<AdminSession, Long> {

    /** A session that can still authenticate: not revoked and not expired. */
    Optional<AdminSession> findByTokenHashAndRevokedAtIsNullAndExpiresAtAfter(String tokenHash, LocalDateTime now);

    @Transactional
    @Modifying
    @Query("update AdminSession s set s.revokedAt = :now where s.tokenHash = :tokenHash and s.revokedAt is null")
    int revoke(@Param("tokenHash") String tokenHash, @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("delete from AdminSession s where s.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") LocalDateTime before);
}
