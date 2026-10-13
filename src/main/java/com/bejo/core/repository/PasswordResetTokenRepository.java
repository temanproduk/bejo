package com.bejo.core.repository;

import com.bejo.core.domain.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    /** The user's newest code that is still usable (not spent, not expired). */
    Optional<PasswordResetToken> findFirstByUserIdAndUsedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
            Long userId, LocalDateTime now);

    /** The user's newest code of any state — used for the resend cooldown. */
    Optional<PasswordResetToken> findFirstByUserIdOrderByCreatedAtDesc(Long userId);

    long countByUserIdAndCreatedAtAfter(Long userId, LocalDateTime since);

    /** Retire every open code for the user (a new request replaces them; a reset spends them all). */
    @Transactional
    @Modifying
    @Query("update PasswordResetToken t set t.usedAt = :now where t.userId = :userId and t.usedAt is null")
    int invalidateOpen(@Param("userId") Long userId, @Param("now") LocalDateTime now);

    /**
     * Atomically spend one guess. A conditional update rather than read-modify-write, so concurrent
     * guesses cannot exceed the limit. 0 rows = the code is spent, expired, or out of attempts.
     */
    @Transactional
    @Modifying
    @Query("update PasswordResetToken t set t.attempts = t.attempts + 1 "
            + "where t.id = :id and t.usedAt is null and t.expiresAt > :now and t.attempts < :max")
    int spendAttempt(@Param("id") Long id, @Param("now") LocalDateTime now, @Param("max") int max);

    /** Single-use: only the caller that flips usedAt from null gets 1 row back. */
    @Transactional
    @Modifying
    @Query("update PasswordResetToken t set t.usedAt = :now where t.id = :id and t.usedAt is null")
    int markUsed(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("delete from PasswordResetToken t where t.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") LocalDateTime before);
}
