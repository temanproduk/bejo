package com.bejo.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Creates a product. {@code categoryId}, {@code costPrice}, {@code stock} and
 * {@code photo} are optional. {@code price}/{@code stock} default to 0 when omitted.
 * The decoded {@code photo} must be ≤500 KB — the service enforces the exact byte
 * limit; the size cap here is a first-line guard on the base64 string length.
 */
public record CreateProductRequestDto(
        @NotBlank(message = "sku wajib diisi")
        @Size(max = 64, message = "sku maksimal 64 karakter")
        String sku,

        @NotBlank(message = "name wajib diisi")
        @Size(max = 255, message = "name maksimal 255 karakter")
        String name,

        Long categoryId,

        @PositiveOrZero(message = "price tidak boleh negatif")
        Long price,

        @PositiveOrZero(message = "costPrice tidak boleh negatif")
        Long costPrice,

        @PositiveOrZero(message = "stock tidak boleh negatif")
        Integer stock,

        @Size(max = 700_000, message = "photo terlalu besar (maksimal 500KB)")
        String photo
) {}
