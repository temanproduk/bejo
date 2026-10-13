package com.bejo.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Confirm an email address with the 6-digit code that was emailed to it. */
public record VerifyEmailRequestDto(
        @NotBlank(message = "username wajib diisi")
        @Size(max = 50, message = "username maksimal 50 karakter")
        String username,

        @NotBlank(message = "Kode wajib diisi")
        @Pattern(regexp = "^[0-9]{6}$", message = "Kode harus 6 digit angka")
        String code
) {}
