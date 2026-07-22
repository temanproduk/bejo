package com.bejo.commerce.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * One line of a purchase. {@code unitPrice} is optional — when omitted the product's
 * current {@code costPrice} is used (0 if it has none).
 */
public record PurchaseItemRequestDto(
        @NotNull(message = "productId wajib diisi")
        Long productId,

        @NotNull(message = "quantity wajib diisi")
        @Positive(message = "quantity harus lebih dari 0")
        Integer quantity,

        @PositiveOrZero(message = "unitPrice tidak boleh negatif")
        Long unitPrice
) {}
