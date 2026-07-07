package com.bejo.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Sets the user's profile picture from a base64-encoded (compressed) image. The
 * decoded image must be at most 1 MB — the service enforces the exact byte limit;
 * the size cap here is a first-line guard on the base64 string length
 * (1 MB binary ≈ 1.4 MB of base64 characters).
 */
public record UpdateProfilePictureRequestDto(
        @NotBlank(message = "profilePicture wajib diisi")
        @Size(max = 1_400_000, message = "profilePicture terlalu besar (maksimal 1MB)")
        String profilePicture
) {}
