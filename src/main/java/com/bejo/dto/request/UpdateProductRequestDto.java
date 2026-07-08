package com.bejo.dto.request;

import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Partial update — a {@code null} field is left unchanged (including managing
 * inventory by setting {@code stock}). To move a product to a different category
 * pass a new {@code categoryId}; category clearing is done by deleting the category.
 */
public record UpdateProductRequestDto(
        @Size(max = 64, message = "sku maksimal 64 karakter")
        String sku,

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
) {
    public boolean isEmpty() {
        return sku == null && name == null && categoryId == null && price == null
                && costPrice == null && stock == null && photo == null;
    }
}
