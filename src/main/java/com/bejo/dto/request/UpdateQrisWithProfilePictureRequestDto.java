package com.bejo.dto.request;

import jakarta.validation.constraints.NotNull;

/** Toggles whether the QRIS image is composed onto the profile picture. */
public record UpdateQrisWithProfilePictureRequestDto(
        @NotNull(message = "enabled wajib diisi")
        Boolean enabled
) {}
