package com.bejo.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record CreateCategoryRequestDto(
        @NotBlank(message = "name wajib diisi")
        @Size(max = 100, message = "name maksimal 100 karakter")
        String name
) {}
