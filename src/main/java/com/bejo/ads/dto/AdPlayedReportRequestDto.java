package com.bejo.ads.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDateTime;

/**
 * Body of {@code POST /api/ads/deliveries/{id}/played} — the app reports it played the ad, when,
 * and (if it could read it) where. Location is optional here: the app only gets it in the
 * foreground, so a background play leaves it null and sends it later via the location endpoint.
 */
public record AdPlayedReportRequestDto(
        @NotNull(message = "Waktu putar wajib diisi")
        LocalDateTime playedAt,

        @DecimalMin(value = "-90.0", message = "latitude harus antara -90 dan 90")
        @DecimalMax(value = "90.0", message = "latitude harus antara -90 dan 90")
        Double latitude,

        @DecimalMin(value = "-180.0", message = "longitude harus antara -180 dan 180")
        @DecimalMax(value = "180.0", message = "longitude harus antara -180 dan 180")
        Double longitude
) {}
