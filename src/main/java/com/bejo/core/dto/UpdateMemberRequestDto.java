package com.bejo.core.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * Partial update of a business member by a super admin. All fields optional; a null field
 * is left unchanged. {@code password}, when present, resets the member's password.
 */
public record UpdateMemberRequestDto(
        @Size(min = 2, max = 100, message = "name harus antara 2 hingga 100 karakter")
        String name,

        @Email(message = "format email tidak valid")
        @Size(max = 255, message = "email maksimal 255 karakter")
        String email,

        @Pattern(regexp = "^\\+[1-9][0-9]{6,14}$",
                 message = "format nomor telepon tidak valid (contoh: +628123456789)")
        String phone,

        @Size(min = 8, message = "password minimal 8 karakter")
        @Pattern(regexp = "^(?=.*[a-zA-Z])(?=.*\\d).+$",
                 message = "password harus mengandung minimal 1 huruf dan 1 angka")
        String password
) {
    public boolean isEmpty() {
        return name == null && email == null && phone == null && password == null;
    }
}
