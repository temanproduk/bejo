package com.bejo.commerce.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * Creates a purchase order (restock). All items are applied atomically — if any line
 * references an unknown product, the whole purchase is rejected and no stock changes.
 * {@code @Valid} cascades the per-line constraints.
 */
public record CreatePurchaseRequestDto(
        @Size(max = 255, message = "note maksimal 255 karakter")
        String note,

        @NotEmpty(message = "items tidak boleh kosong")
        @Size(max = 200, message = "maksimal 200 item per pembelian")
        @Valid
        List<PurchaseItemRequestDto> items
) {}
