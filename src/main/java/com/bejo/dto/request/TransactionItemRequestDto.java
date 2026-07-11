package com.bejo.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * One product sold within a manual transaction: a (product, quantity) tuple. The unit
 * price is taken from the product's current selling price at sale time (not supplied by
 * the client). Stock is validated + reduced when the transaction is created.
 */
public record TransactionItemRequestDto(
        @NotNull(message = "productId wajib diisi")
        Long productId,

        @NotNull(message = "quantity wajib diisi")
        @Positive(message = "quantity harus lebih dari 0")
        Integer quantity
) {}
