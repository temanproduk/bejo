package com.bejo.dto.request;

import jakarta.validation.constraints.NotBlank;

public record UpdateProfileRequest(
        String name,
        @NotBlank(message = "Email wajib diisi") String email,
        @NotBlank(message = "Nomor telepon wajib diisi") String phone
) {}
