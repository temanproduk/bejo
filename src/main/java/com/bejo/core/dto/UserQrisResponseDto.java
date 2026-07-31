package com.bejo.core.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * One user's assigned QRIS. {@code qrisCode} (the EMV payload) is included on the single-user get
 * and omitted from the list — {@code @JsonInclude(NON_NULL)}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserQrisResponseDto(
        Long userId,
        String username,
        String terminalId,
        String qrisCode,
        // Only for assignments made before the switch, whose stored value is still a base64
        // image the server cannot decode into a code. Null otherwise.
        String legacyQrisImage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
