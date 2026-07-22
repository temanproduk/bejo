package com.bejo.commerce.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * One line of a stock adjustment (opname): {@code stock} is the new absolute counted
 * quantity the product should be set to.
 */
public record StockAdjustmentItemDto(
        @NotNull(message = "productId wajib diisi")
        Long productId,

        @NotNull(message = "stock wajib diisi")
        @PositiveOrZero(message = "stock tidak boleh negatif")
        Integer stock
) {}
