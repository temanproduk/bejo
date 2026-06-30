package com.bejo.repository;

import com.bejo.entity.UserQris;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserQrisRepository extends JpaRepository<UserQris, Long> {
    Optional<UserQris> findByUsername(String username);
}
