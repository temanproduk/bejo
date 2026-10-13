package com.bejo.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Ask for another verification code: it is emailed to the address on file for this username. */
public record ResendVerificationRequestDto(
        @NotBlank(message = "username wajib diisi")
        @Size(max = 50, message = "username maksimal 50 karakter")
        String username
) {}
