package com.bejo.core.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * One user's assigned QRIS. {@code qrisCode} is included on the single-user get and omitted from
 * the list — {@code @JsonInclude(NON_NULL)}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserQrisResponseDto(
        Long userId,
        String username,
        String terminalId,
        String qrisCode,
        String merchantName,
        String nmid,
        String acquirer,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
