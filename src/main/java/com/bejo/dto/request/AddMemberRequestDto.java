package com.bejo.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Payload for a super admin to add a user to their business. No business fields — the
 * new user joins the caller's existing business as a {@code MEMBER}.
 */
public record AddMemberRequestDto(
        @NotBlank(message = "username wajib diisi")
        @Size(min = 3, max = 50, message = "username harus antara 3 hingga 50 karakter")
        @Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "username hanya boleh mengandung huruf, angka, dan underscore")
        String username,

        @NotBlank(message = "password wajib diisi")
        @Size(min = 8, message = "password minimal 8 karakter")
        @Pattern(regexp = "^(?=.*[a-zA-Z])(?=.*\\d).+$",
                 message = "password harus mengandung minimal 1 huruf dan 1 angka")
        String password,

        @NotBlank(message = "name wajib diisi")
        @Size(min = 2, max = 100, message = "name harus antara 2 hingga 100 karakter")
        String name,

        @NotBlank(message = "email wajib diisi")
        @Email(message = "format email tidak valid")
        @Size(max = 255, message = "email maksimal 255 karakter")
        String email,

        @NotBlank(message = "phone wajib diisi")
        @Pattern(regexp = "^\\+[1-9][0-9]{6,14}$",
                 message = "format nomor telepon tidak valid (contoh: +628123456789)")
        String phone
) {}
