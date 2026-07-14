package com.bejo.dto.request;

import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/presences/{id}/respond} — the member's reply to a PING. The body
 * (and the note in it) is optional: tapping to acknowledge is enough, but the member may say
 * where they are or what they're doing. The note is stored on the PING entry itself, so the
 * super admin reads it in the attendance history next to the response time.
 */
public record PresenceRespondRequestDto(
        @Size(max = 255, message = "note maksimal 255 karakter")
        String note
) {}
