package com.bejo.core.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record RegisterRequestDto(
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
        String phone,

        @NotBlank(message = "businessName wajib diisi")
        @Size(max = 255, message = "businessName maksimal 255 karakter")
        String businessName,

        String businessAddress,

        @Size(max = 100, message = "bankName maksimal 100 karakter")
        String bankName,

        @Pattern(regexp = "^$|^[0-9]{6,20}$",
                 message = "bankNumber harus berupa angka (6-20 digit)")
        String bankNumber,

        @Size(max = 100, message = "bankAccountName maksimal 100 karakter")
        String bankAccountName,

        // Business classification — set ONCE at registration, cannot be changed later.
        // Optional: omitting size/industry applies the configured default. Validated against
        // the configurable app.business.sizes / app.business.industries lists in the service.
        String size,

        String industry,

        // Optional store photo — base64, <= 1 MB decoded (same spec as the business picture)
        String storePhoto,

        // QRIS merchant PAN — routes inbound payment callbacks to this business (the callback
        // carries merchantPan, not the NMID). Optional; set at registration.
        @Size(max = 50, message = "merchantPan maksimal 50 karakter")
        String merchantPan
) {}
