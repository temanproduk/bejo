package com.bejo.dto.request;

import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/presences/pings} — the super admin's spot-check to every
 * member. Everything is optional; the note (when given) doubles as the push body.
 */
public record PresencePingRequestDto(
        @Size(max = 255, message = "note maksimal 255 karakter")
        String note
) {}
