package com.bejo.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record ChangePasswordRequestDto(
        @NotBlank(message = "Password saat ini wajib diisi")
        String oldPassword,

        @NotBlank(message = "Password baru wajib diisi")
        @Size(min = 8, message = "Password baru minimal 8 karakter")
        @Pattern(regexp = "^(?=.*[a-zA-Z])(?=.*\\d).+$",
                 message = "Password baru harus mengandung minimal 1 huruf dan 1 angka")
        String newPassword
) {}
