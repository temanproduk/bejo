package com.bejo.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PUT /api/qris/users/{id}} — the main account assigns one user their own QRIS
 * code + terminal id (same merchant PAN, distinct terminal). Both required.
 *
 * <p>{@code qrisCode} is the EMV payload the app decodes from that terminal's QR, not an image.
 */
public record SetUserQrisRequestDto(
        @NotBlank(message = "terminalId wajib diisi")
        @Size(max = 50, message = "terminalId maksimal 50 karakter")
        String terminalId,

        @NotBlank(message = "qrisCode wajib diisi")
        @Size(max = 1024, message = "qrisCode terlalu panjang (maksimal 1024 karakter)")
        String qrisCode
) {}
