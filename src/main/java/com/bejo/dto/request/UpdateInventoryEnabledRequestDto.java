package com.bejo.dto.request;

import jakarta.validation.constraints.NotNull;

/** Toggles whether inventory management is enabled for the merchant. */
public record UpdateInventoryEnabledRequestDto(
        @NotNull(message = "enabled wajib diisi")
        Boolean enabled
) {}
