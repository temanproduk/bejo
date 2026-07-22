package com.bejo.commerce.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A purchase order with its line items. {@code items} is populated on create and on
 * the single-purchase detail; the list endpoint omits it (summary only).
 */
public record StockMutationResponseDto(
        Long id,
        String type,
        long totalAmount,
        String note,
        LocalDateTime createdAt,
        List<StockMutationItemResponseDto> items
) {
    public record StockMutationItemResponseDto(
            Long productId,
            String sku,
            String name,
            int previousStock,
            int newStock,
            int quantityChange,
            Long unitPrice,
            Long subtotal
    ) {}
}
