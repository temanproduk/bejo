package com.bejo.core.dto;

import java.time.LocalDateTime;

/** A user (member) of a business. */
public record MemberResponseDto(
        Long id,
        String username,
        String name,
        String email,
        String phone,
        String businessRole,
        LocalDateTime createdAt
) {}
