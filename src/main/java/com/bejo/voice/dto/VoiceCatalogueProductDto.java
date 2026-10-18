package com.bejo.voice.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * One product of the merchant's catalogue as the app has it. The voice module never reads the product tables (it
 * depends on {@code shared} only), so the app sends the list along with the recording and the service matches the
 * spoken product names against it. {@code price} is the selling price in rupiah; it is what a matched item is
 * priced at, so it is the app's own number, never one the speech model produced.
 */
public record VoiceCatalogueProductDto(
        @NotNull(message = "products[].id wajib diisi")
        Long id,
        @NotBlank(message = "products[].name wajib diisi")
        @Size(max = 255, message = "products[].name maksimal 255 karakter")
        String name,
        @PositiveOrZero(message = "products[].price tidak boleh negatif")
        Long price
) {}
