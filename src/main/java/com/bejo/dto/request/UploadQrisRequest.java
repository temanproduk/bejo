package com.bejo.dto.request;

import jakarta.validation.constraints.NotBlank;

public record UploadQrisRequest(
        @NotBlank(message = "image wajib diisi") String image
) {}
