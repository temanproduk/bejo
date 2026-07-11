package com.bejo.repository;

import com.bejo.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {
    Page<Product> findByBusinessIdOrderByNameAsc(Long businessId, Pageable pageable);
    Page<Product> findByBusinessIdAndCategoryIdOrderByNameAsc(Long businessId, Long categoryId, Pageable pageable);
    Optional<Product> findByIdAndBusinessId(Long id, Long businessId);
    boolean existsByBusinessIdAndSku(Long businessId, String sku);

    // Un-categorize a business's products when their category is deleted
    @Modifying
    @Query("update Product p set p.categoryId = null where p.businessId = :businessId and p.categoryId = :categoryId")
    int clearCategory(@Param("businessId") Long businessId, @Param("categoryId") Long categoryId);
}
