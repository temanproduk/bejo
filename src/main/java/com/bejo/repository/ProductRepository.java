package com.bejo.repository;

import com.bejo.entity.Product;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {
    List<Product> findByUsernameOrderByNameAsc(String username);
    List<Product> findByUsernameAndCategoryIdOrderByNameAsc(String username, Long categoryId);
    Optional<Product> findByIdAndUsername(Long id, String username);
    boolean existsByUsernameAndSku(String username, String sku);

    // Un-categorize a merchant's products when their category is deleted
    @Modifying
    @Query("update Product p set p.categoryId = null where p.username = :username and p.categoryId = :categoryId")
    int clearCategory(@Param("username") String username, @Param("categoryId") Long categoryId);
}
