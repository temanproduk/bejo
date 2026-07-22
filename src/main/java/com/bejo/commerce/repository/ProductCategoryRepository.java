package com.bejo.commerce.repository;

import com.bejo.commerce.domain.ProductCategory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductCategoryRepository extends JpaRepository<ProductCategory, Long> {
    List<ProductCategory> findByBusinessIdOrderByNameAsc(Long businessId);
    Optional<ProductCategory> findByIdAndBusinessId(Long id, Long businessId);
    boolean existsByBusinessIdAndName(Long businessId, String name);
}
