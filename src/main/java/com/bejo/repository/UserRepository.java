package com.bejo.repository;

import com.bejo.entity.User;
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

    // Resolve the business scope for an authenticated user (business-owned data is keyed
    // on business_id, not username).
    @Query("SELECT u.businessId FROM User u WHERE u.username = :username AND u.deletedAt IS NULL")
    Optional<Long> findBusinessIdByUsername(@Param("username") String username);

    // Members of a business (active only), oldest first (the creator/super admin first)
    List<User> findByBusinessIdAndDeletedAtIsNullOrderByCreatedAtAsc(Long businessId);

    // How many active users a business has (for the per-business user cap)
    long countByBusinessIdAndDeletedAtIsNull(Long businessId);

    Optional<User> findByIdAndBusinessIdAndDeletedAtIsNull(Long id, Long businessId);

    @Query("SELECT u.username FROM User u WHERE u.businessId = :businessId AND u.deletedAt IS NULL")
    List<String> findUsernamesByBusinessId(@Param("businessId") Long businessId);
}
