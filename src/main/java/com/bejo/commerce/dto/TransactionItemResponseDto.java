package com.bejo.commerce.dto;

/**
 * A product sold within a transaction. Snapshots the product's sku/name/unit price at
 * sale time; {@code productId} may point to a product that was since edited or deleted.
 */
public record TransactionItemResponseDto(
        Long productId,
        String sku,
        String name,
        int quantity,
        long unitPrice,
        long subtotal
) {}
