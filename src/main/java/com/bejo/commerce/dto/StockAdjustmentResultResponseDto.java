package com.bejo.commerce.dto;

import java.util.List;

/**
 * Result of a bulk stock adjustment — a variance report the client can show: for each
 * product, the {@code previousStock}, the {@code newStock} it was set to, and the
 * {@code difference} ({@code newStock - previousStock}, negative = shrinkage).
 */
public record StockAdjustmentResultResponseDto(
        int total,
        List<ItemResult> items
) {
    public record ItemResult(
            Long productId,
            String sku,
            String name,
            int previousStock,
            int newStock,
            int difference
    ) {}
}
