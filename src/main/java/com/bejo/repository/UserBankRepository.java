package com.bejo.repository;

import com.bejo.entity.UserBank;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserBankRepository extends JpaRepository<UserBank, Long> {
    Optional<UserBank> findByUsername(String username);
}
