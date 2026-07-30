package com.bejo.core.repository;

import com.bejo.core.domain.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    // Only ACTIVE users are visible: a soft-deleted row keeps its username but must not
    // be logged in, resolved, or block re-registration of that (released) username.
    Optional<User> findByUsernameAndDeletedAtIsNull(String username);

    boolean existsByUsernameAndDeletedAtIsNull(String username);

    // The active user's current password hash — the per-request auth check needs both
    // "is this user still active?" (empty = gone/soft-deleted) and the hash the token was
    // bound to, so it resolves them in one query instead of two.
    @Query("SELECT u.passwordHash FROM User u WHERE u.username = :username AND u.deletedAt IS NULL")
    Optional<String> findPasswordHashByUsername(@Param("username") String username);

    // Resolve the business scope for an authenticated user (business-owned data is keyed
    // on business_id, not username).
    @Query("SELECT u.businessId FROM User u WHERE u.username = :username AND u.deletedAt IS NULL")
    Optional<Long> findBusinessIdByUsername(@Param("username") String username);

    // Members of a business (active only), oldest first (the creator/super admin first)
    List<User> findByBusinessIdAndDeletedAtIsNullOrderByCreatedAtAsc(Long businessId);

    // How many active users a business has (for the per-business user cap)
    long countByBusinessIdAndDeletedAtIsNull(Long businessId);

    Optional<User> findByIdAndBusinessIdAndDeletedAtIsNull(Long id, Long businessId);

    // Users of a business holding a given role (active only) — the business's MEMBERs are
    // who a presence ping fans out to.
    List<User> findByBusinessIdAndBusinessRoleAndDeletedAtIsNullOrderByCreatedAtAsc(Long businessId, String businessRole);

    // The business's super admin — the recipient of the clock-in/clock-out pushes. A business
    // has exactly one, but findFirst keeps a malformed row from blowing up the push step.
    Optional<User> findFirstByBusinessIdAndBusinessRoleAndDeletedAtIsNull(Long businessId, String businessRole);

    @Query("SELECT u.username FROM User u WHERE u.businessId = :businessId AND u.deletedAt IS NULL")
    List<String> findUsernamesByBusinessId(@Param("businessId") Long businessId);

    // Which of these users are still active — the presence history keeps a removed member's
    // entries, and flags them so the app can tell them apart.
    @Query("SELECT u.id FROM User u WHERE u.id IN :ids AND u.deletedAt IS NULL")
    List<Long> findActiveIdsByIdIn(@Param("ids") List<Long> ids);
}
