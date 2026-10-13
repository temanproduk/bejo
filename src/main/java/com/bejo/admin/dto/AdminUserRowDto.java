package com.bejo.admin.dto;

import java.time.LocalDateTime;

/** One row of the dashboard's user list: the user plus the business they belong to. No credentials. */
public record AdminUserRowDto(
        Long id,
        String username,
        String name,
        String email,
        String phone,
        Long businessId,
        String businessName,
        String businessRole,
        String accountType,
        boolean emailVerified,
        LocalDateTime createdAt,
        // Non-null = soft-deleted (the username has been released)
        LocalDateTime deletedAt
) {}
