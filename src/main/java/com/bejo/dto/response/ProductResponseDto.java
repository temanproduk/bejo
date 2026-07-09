package com.bejo.dto.response;

import java.time.LocalDateTime;

/**
 * Product with its resolved category name. {@code photo} (base64) is only populated
 * on the single-product detail response; list responses omit it to keep the payload
 * small.
 */
public record ProductResponseDto(
        Long id,
        String sku,
        String name,
        String shortDescription,
        Long categoryId,
        String categoryName,
        long price,
        Long costPrice,
        int stock,
        String photo,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
