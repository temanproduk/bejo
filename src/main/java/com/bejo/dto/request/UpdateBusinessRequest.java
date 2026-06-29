package com.bejo.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UpdateBusinessRequest(
        @NotBlank(message = "businessName wajib diisi")
        @Size(max = 255, message = "businessName maksimal 255 karakter")
        String businessName,

        String businessAddress
) {}
