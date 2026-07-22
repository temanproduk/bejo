package com.bejo.core.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

/**
 * One user's assigned QRIS. {@code qrisImage} is included on the single-user get and omitted from
 * the list (to keep the payload small — {@code @JsonInclude(NON_NULL)}).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record UserQrisResponseDto(
        String username,
        String terminalId,
        String qrisImage,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {}
