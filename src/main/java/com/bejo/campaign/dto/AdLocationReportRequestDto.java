package com.bejo.campaign.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code POST /api/ads/deliveries/{id}/location} — the deferred play location (step 8),
 * sent once the app can read it (only in the foreground). Both coordinates are required: this
 * call exists precisely to supply the location the play report couldn't.
 */
public record AdLocationReportRequestDto(
        @NotNull(message = "latitude wajib diisi")
        @DecimalMin(value = "-90.0", message = "latitude harus antara -90 dan 90")
        @DecimalMax(value = "90.0", message = "latitude harus antara -90 dan 90")
        Double latitude,

        @NotNull(message = "longitude wajib diisi")
        @DecimalMin(value = "-180.0", message = "longitude harus antara -180 dan 180")
        @DecimalMax(value = "180.0", message = "longitude harus antara -180 dan 180")
        Double longitude
) {}
