package com.bejo.commerce.repository;

import com.bejo.commerce.domain.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

@Repository
public interface ProductRepository extends JpaRepository<Product, Long> {
    // List queries use the ProductSummary projection so the photo column is never selected;
    // the single-product detail loads the full entity (photo included).
    Page<ProductSummary> findSummaryByBusinessIdOrderByNameAsc(Long businessId, Pageable pageable);
    Page<ProductSummary> findSummaryByBusinessIdAndCategoryIdOrderByNameAsc(Long businessId, Long categoryId, Pageable pageable);
    Optional<Product> findByIdAndBusinessId(Long id, Long businessId);
    boolean existsByBusinessIdAndSku(Long businessId, String sku);

    // Un-categorize a business's products when their category is deleted
    @Modifying
    @Query("update Product p set p.categoryId = null where p.businessId = :businessId and p.categoryId = :categoryId")
    int clearCategory(@Param("businessId") Long businessId, @Param("categoryId") Long categoryId);

    /**
     * Atomically draw stock down, refusing to go negative. Returns the number of rows changed:
     * {@code 1} on success, {@code 0} when the product is gone or no longer has enough stock.
     *
     * <p>This is the guard against lost updates — read-modify-write in the service let two
     * concurrent sales of the same product each read the same starting stock and each write
     * back their own result, silently discarding one sale. Doing the arithmetic and the
     * "enough stock?" test in a single statement makes the database the arbiter.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
           update Product p set p.stock = p.stock - :quantity
           where p.id = :id and p.businessId = :businessId and p.stock >= :quantity
           """)
    int decreaseStock(@Param("id") Long id, @Param("businessId") Long businessId, @Param("quantity") int quantity);

    /** Atomically give stock back (edit that reduces a line, or a deleted sale). */
    @Modifying(flushAutomatically = true)
    @Query("update Product p set p.stock = p.stock + :quantity where p.id = :id and p.businessId = :businessId")
    int increaseStock(@Param("id") Long id, @Param("businessId") Long businessId, @Param("quantity") int quantity);

    /**
     * The authoritative stock of several products, read straight from the database — a JPQL
     * projection is not served from the persistence context, so it reflects the atomic updates
     * just applied rather than the now-stale values on the loaded entities.
     */
    @Query("select p.id as id, p.stock as stock from Product p where p.id in :ids")
    List<ProductStock> findStockByIdIn(@Param("ids") Collection<Long> ids);
}
