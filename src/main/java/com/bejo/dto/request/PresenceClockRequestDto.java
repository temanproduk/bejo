package com.bejo.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

/** Body of {@code POST /api/presences/clock-in} and {@code /clock-out}. */
public record PresenceClockRequestDto(
        @NotNull(message = "latitude wajib diisi")
        @DecimalMin(value = "-90.0", message = "latitude harus antara -90 dan 90")
        @DecimalMax(value = "90.0", message = "latitude harus antara -90 dan 90")
        Double latitude,

        @NotNull(message = "longitude wajib diisi")
        @DecimalMin(value = "-180.0", message = "longitude harus antara -180 dan 180")
        @DecimalMax(value = "180.0", message = "longitude harus antara -180 dan 180")
        Double longitude,

        // The moment of the clock-in/out, in the device's local (WIB) wall-clock time —
        // client-supplied so an entry made offline keeps its real time when it syncs.
        @NotNull(message = "clockDatetime wajib diisi")
        LocalDateTime clockDatetime,

        @Size(max = 255, message = "note maksimal 255 karakter")
        String note
) {}
