package com.bejo.core.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;

/** One attendance entry — a clock-in, a clock-out, or a ping (with its response, if any). */
public record PresenceResponseDto(
        Long id,
        Long userId,
        String username,
        String name,
        String type,
        LocalDateTime clockDatetime,
        LocalDate presenceDate,
        Double latitude,
        Double longitude,
        String note,
        LocalDateTime responseDatetime,
        // False once the member has been removed from the business — their entries stay in
        // the history (nothing is lost), and this lets the app mark them as ex-staff.
        boolean userActive,
        LocalDateTime createdAt
) {}
