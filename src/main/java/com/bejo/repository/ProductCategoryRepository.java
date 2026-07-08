package com.bejo.repository;

import com.bejo.entity.ProductCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductCategoryRepository extends JpaRepository<ProductCategory, Long> {
    List<ProductCategory> findByUsernameOrderByNameAsc(String username);
    Optional<ProductCategory> findByIdAndUsername(Long id, String username);
    boolean existsByUsernameAndName(String username, String name);
}
