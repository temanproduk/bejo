package com.bejo.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Bulk stock adjustment (stock opname): sets several products' stock to their newly
 * counted quantities at once. Applied atomically — if any line references a product
 * the merchant doesn't own, the whole adjustment is rejected and no stock changes.
 */
public record StockAdjustmentRequestDto(
        @NotEmpty(message = "items tidak boleh kosong")
        @Size(max = 500, message = "maksimal 500 item per penyesuaian")
        @Valid
        List<StockAdjustmentItemDto> items
) {}
