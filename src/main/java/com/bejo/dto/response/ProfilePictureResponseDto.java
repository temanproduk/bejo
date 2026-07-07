package com.bejo.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * The user's profile picture as a base64 string, or {@code null} when unset.
 * {@code @JsonInclude(ALWAYS)} overrides the global non-null inclusion so the
 * {@code profilePicture} key is always present (explicitly null) — no placeholder.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record ProfilePictureResponseDto(
        String profilePicture
) {}
