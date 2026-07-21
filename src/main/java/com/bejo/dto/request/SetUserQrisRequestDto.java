package com.bejo.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PUT /api/qris/users/{username}} — the main account assigns one user their own
 * QRIS image + terminal id (same merchant PAN, distinct terminal). Both required.
 */
public record SetUserQrisRequestDto(
        @NotBlank(message = "terminalId wajib diisi")
        @Size(max = 50, message = "terminalId maksimal 50 karakter")
        String terminalId,

        @NotBlank(message = "qrisImage wajib diisi")
        @Size(max = 7_000_000, message = "qrisImage terlalu besar (maksimal ±5MB)")
        String qrisImage
) {}
