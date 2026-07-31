package com.bejo.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PUT /api/qris/users/{id}} — gives one user their own QRIS.
 *
 * <p>Only the code is supplied: the terminal id, merchant name, NMID and acquirer are all parsed
 * out of it, so they can never drift from the QR they describe. A code that carries no terminal
 * label simply stores null.
 */
public record SetUserQrisRequestDto(
        @NotBlank(message = "qrisCode wajib diisi")
        @Size(max = 1024, message = "qrisCode terlalu panjang (maksimal 1024 karakter)")
        String qrisCode
) {}
