package com.bejo;

import com.bejo.commerce.domain.Product;
import com.bejo.commerce.repository.ProductRepository;
import com.bejo.core.domain.Business;
import com.bejo.core.repository.BusinessRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guards the commerce → core soft reference: after the modular split, commerce tables reference
 * {@code businesses(id)} by a plain {@code business_id} column with <b>no</b> foreign key, so the
 * database no longer rejects a row whose business is missing. This proves the orphan-detection query
 * (see {@code scripts/check-orphans.sql}) actually catches such a row and stays silent for a valid
 * one. It runs in the normal suite, so the safety net is exercised on CI (mvn test).
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("test")
@Transactional
class OrphanDetectionTest {

    // Mirrors scripts/check-orphans.sql for the products table (any non-zero count is an orphan).
    // Tables are schema-qualified (commerce/core) after the per-module schema split.
    private static final String ORPHAN_QUERY =
            "SELECT COUNT(*) FROM commerce.products p WHERE NOT EXISTS (SELECT 1 FROM core.businesses b WHERE b.id = p.business_id)";

    @Autowired JdbcTemplate jdbc;
    @Autowired BusinessRepository businesses;
    @Autowired ProductRepository products;

    @Test
    void detectsAProductWhoseBusinessDoesNotExist() {
        Business business = new Business();
        business.setName("Warung Uji");
        business.setSize("MICRO");
        business.setIndustry("F&B");
        businesses.save(business);

        // A product tied to a real business is fine…
        products.save(product(business.getId(), "SKU-OK"));
        assertThat(orphanCount()).isZero();

        // …a product tied to a business that doesn't exist is an orphan the query must catch.
        products.save(product(999_999L, "SKU-ORPHAN"));
        assertThat(orphanCount()).isEqualTo(1);
    }

    private static Product product(Long businessId, String sku) {
        Product p = new Product();
        p.setBusinessId(businessId);
        p.setSku(sku);
        p.setName("Uji");
        return p;
    }

    private long orphanCount() {
        products.flush();  // ensure the inserts hit the DB before the native count reads them
        Long n = jdbc.queryForObject(ORPHAN_QUERY, Long.class);
        return n == null ? 0 : n;
    }
}
