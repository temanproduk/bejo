package com.bejo.repository;

import com.bejo.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    // findFirst (not findBy) so a duplicate NMID in the data never throws —
    // routing a callback should degrade gracefully, not 500
    Optional<User> findFirstByNmid(String nmid);

    boolean existsByUsername(String username);
}
