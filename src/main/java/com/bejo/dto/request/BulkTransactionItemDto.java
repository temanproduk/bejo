package com.bejo.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * One entry in a bulk transaction sync: the transaction payload plus the
 * client-generated {@code idempotencyKey} that makes re-sync safe. {@code @Valid}
 * cascades the nested {@link AddTransactionRequestDto} constraints when the service
 * validates the item programmatically.
 */
public record BulkTransactionItemDto(
        @NotBlank(message = "idempotencyKey wajib diisi")
        @Size(max = 255, message = "idempotencyKey maksimal 255 karakter")
        String idempotencyKey,

        @NotNull(message = "transaction wajib diisi")
        @Valid
        AddTransactionRequestDto transaction
) {}
