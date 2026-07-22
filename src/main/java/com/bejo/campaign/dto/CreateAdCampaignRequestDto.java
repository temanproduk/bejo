package com.bejo.campaign.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Body of {@code POST /api/ads/campaigns} (admin) — creates a campaign + its one delivery. */
public record CreateAdCampaignRequestDto(
        @NotBlank(message = "Nama kampanye wajib diisi")
        @Size(max = 255, message = "Nama kampanye maksimal 255 karakter")
        String name,

        @Size(max = 255, message = "Nama brand maksimal 255 karakter")
        String brand,

        // The ≤10s voice clip, base64-encoded. Decoded size + hash are computed server-side.
        @NotBlank(message = "Audio wajib diisi")
        String audioBase64,

        @NotBlank(message = "Tipe audio wajib diisi")
        @Size(max = 100, message = "Tipe audio maksimal 100 karakter")
        String contentType,

        @NotBlank(message = "Target pengguna wajib diisi")
        @Size(max = 50, message = "Target pengguna maksimal 50 karakter")
        String targetUsername,

        @NotBlank(message = "Target perangkat wajib diisi")
        String targetDeviceId
) {}
