package com.bejo.core.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record UpdateProfileRequestDto(
        @NotBlank(message = "Nama wajib diisi")
        @Size(min = 2, max = 100, message = "Nama harus antara 2 hingga 100 karakter")
        String name,

        @NotBlank(message = "Email wajib diisi")
        @Email(message = "Format email tidak valid")
        String email,

        @NotBlank(message = "Nomor telepon wajib diisi")
        @Pattern(regexp = "^\\+[1-9][0-9]{6,14}$",
                 message = "Format nomor telepon tidak valid (contoh: +628123456789)")
        String phone
) {}
