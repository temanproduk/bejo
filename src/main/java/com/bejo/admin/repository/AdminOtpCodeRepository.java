package com.bejo.admin.repository;

import com.bejo.admin.domain.AdminOtpCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

public interface AdminOtpCodeRepository extends JpaRepository<AdminOtpCode, Long> {

    /** The address's newest code that is still usable (not spent, not expired). */
    Optional<AdminOtpCode> findFirstByEmailAndUsedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(
            String email, LocalDateTime now);

    /** The address's newest code of any state — used for the resend cooldown. */
    Optional<AdminOtpCode> findFirstByEmailOrderByCreatedAtDesc(String email);

    long countByEmailAndCreatedAtAfter(String email, LocalDateTime since);

    /** Retire every open code for the address (a new request replaces them; a sign-in spends them all). */
    @Transactional
    @Modifying
    @Query("update AdminOtpCode c set c.usedAt = :now where c.email = :email and c.usedAt is null")
    int invalidateOpen(@Param("email") String email, @Param("now") LocalDateTime now);

    /**
     * Atomically spend one guess. A conditional update rather than read-modify-write, so concurrent
     * guesses cannot exceed the limit. 0 rows = the code is spent, expired, or out of attempts.
     */
    @Transactional
    @Modifying
    @Query("update AdminOtpCode c set c.attempts = c.attempts + 1 "
            + "where c.id = :id and c.usedAt is null and c.expiresAt > :now and c.attempts < :max")
    int spendAttempt(@Param("id") Long id, @Param("now") LocalDateTime now, @Param("max") int max);

    /** Single-use: only the caller that flips usedAt from null gets 1 row back. */
    @Transactional
    @Modifying
    @Query("update AdminOtpCode c set c.usedAt = :now where c.id = :id and c.usedAt is null")
    int markUsed(@Param("id") Long id, @Param("now") LocalDateTime now);

    @Transactional
    @Modifying
    @Query("delete from AdminOtpCode c where c.expiresAt < :before")
    int deleteExpiredBefore(@Param("before") LocalDateTime before);
}
