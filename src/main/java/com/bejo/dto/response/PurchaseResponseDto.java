package com.bejo.dto.response;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A purchase order with its line items. {@code items} is populated on create and on
 * the single-purchase detail; the list endpoint omits it (summary only).
 */
public record PurchaseResponseDto(
        Long id,
        long totalAmount,
        String note,
        LocalDateTime createdAt,
        List<PurchaseItemResponseDto> items
) {
    public record PurchaseItemResponseDto(
            Long productId,
            String sku,
            String name,
            long unitPrice,
            int quantity,
            long subtotal
    ) {}
}
