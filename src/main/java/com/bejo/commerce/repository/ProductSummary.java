package com.bejo.commerce.repository;

import java.time.LocalDateTime;

/**
 * A product without its {@code photo}, for list responses.
 *
 * <p>Spring Data narrows the generated {@code SELECT} to exactly these properties, so listing
 * products no longer drags every base64 photo (up to 500 KB each, up to 200 rows a page) out of
 * the database and through the heap only for the mapper to discard it.
 */
public interface ProductSummary {

    Long getId();
    String getSku();
    String getName();
    String getShortDescription();
    Long getCategoryId();
    long getPrice();
    Long getCostPrice();
    int getStock();
    LocalDateTime getCreatedAt();
    LocalDateTime getUpdatedAt();
}
