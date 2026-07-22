package com.bejo.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/users/me/upgrade} — upgrades a self-service account to be managed by
 * Bejo. Both the merchant PAN and the NMID are <b>required</b> (they wire up QRIS callback
 * routing); a missing/blank value is rejected.
 */
public record UpgradeAccountRequestDto(
        @NotBlank(message = "merchantPan wajib diisi")
        @Size(max = 50, message = "merchantPan maksimal 50 karakter")
        String merchantPan,

        @NotBlank(message = "nmid wajib diisi")
        @Size(max = 50, message = "nmid maksimal 50 karakter")
        String nmid
) {}
