package com.bejo.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record UploadQrisRequest(
        @NotBlank(message = "qrisImage wajib diisi")
        @Size(max = 7_000_000, message = "qrisImage terlalu besar (maksimal ±5MB)")
        String qrisImage
) {}
