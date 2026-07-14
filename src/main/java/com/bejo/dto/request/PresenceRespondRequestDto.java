package com.bejo.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/presences/{id}/respond} — the member's reply to a PING.
 *
 * <p>The <b>location is required</b>: a ping is a spot-check, so the answer is only worth
 * anything if it says where the member actually is (the same {@code latitude}/{@code
 * longitude} they'd send on a clock-in). The {@code note} stays optional — free text for
 * where they are or what they're doing, which the super admin reads in the history.
 */
public record PresenceRespondRequestDto(
        @NotNull(message = "latitude wajib diisi")
        @DecimalMin(value = "-90.0", message = "latitude harus antara -90 dan 90")
        @DecimalMax(value = "90.0", message = "latitude harus antara -90 dan 90")
        Double latitude,

        @NotNull(message = "longitude wajib diisi")
        @DecimalMin(value = "-180.0", message = "longitude harus antara -180 dan 180")
        @DecimalMax(value = "180.0", message = "longitude harus antara -180 dan 180")
        Double longitude,

        @Size(max = 255, message = "note maksimal 255 karakter")
        String note
) {}
