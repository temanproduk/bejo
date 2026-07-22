package com.bejo.commerce.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Bulk stock adjustment (stock opname): sets several products' stock to their newly
 * counted quantities at once. Applied atomically — if any line references a product
 * the merchant doesn't own, the whole adjustment is rejected and no stock changes.
 * The optional {@code note} is stored on the resulting ADJUSTMENT stock mutation.
 */
public record StockAdjustmentRequestDto(
        @Size(max = 255, message = "note maksimal 255 karakter")
        String note,

        @NotEmpty(message = "items tidak boleh kosong")
        @Size(max = 500, message = "maksimal 500 item per penyesuaian")
        @Valid
        List<StockAdjustmentItemDto> items
) {}
