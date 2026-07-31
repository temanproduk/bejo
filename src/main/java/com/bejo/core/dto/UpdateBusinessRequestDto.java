package com.bejo.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code PATCH /api/users/me/business}.
 *
 * <p>{@code nmid} is deliberately absent: it now belongs to the QRIS, not the business, and is
 * parsed from the QRIS code at upload so it can never disagree with the QR it labels.
 */
public record UpdateBusinessRequestDto(
        @NotBlank(message = "businessName wajib diisi")
        @Size(max = 255, message = "businessName maksimal 255 karakter")
        String businessName,

        String businessAddress
) {}
