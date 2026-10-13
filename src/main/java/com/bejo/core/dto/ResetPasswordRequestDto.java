package com.bejo.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Finish a password reset with the emailed code. Same password rules as registration. */
public record ResetPasswordRequestDto(
        @NotBlank(message = "username wajib diisi")
        @Size(max = 50, message = "username maksimal 50 karakter")
        String username,

        @NotBlank(message = "Kode wajib diisi")
        @Pattern(regexp = "^[0-9]{6}$", message = "Kode harus 6 digit angka")
        String code,

        @NotBlank(message = "Password baru wajib diisi")
        @Size(min = 8, message = "Password baru minimal 8 karakter")
        @Pattern(regexp = "^(?=.*[a-zA-Z])(?=.*\\d).+$",
                 message = "Password baru harus mengandung minimal 1 huruf dan 1 angka")
        String newPassword
) {}
