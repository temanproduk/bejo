package com.bejo.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/users/me/upgrade} — upgrades a self-service account to be managed by
 * Bejo. The merchant PAN is <b>required</b> — it is the key an inbound QRIS
 * callback routes on. NMID is not here: it belongs to the QRIS, parsed from the code on upload.
 */
public record UpgradeAccountRequestDto(
        @NotBlank(message = "merchantPan wajib diisi")
        @Size(max = 50, message = "merchantPan maksimal 50 karakter")
        String merchantPan
) {}
