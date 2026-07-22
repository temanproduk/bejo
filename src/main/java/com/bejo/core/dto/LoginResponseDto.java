package com.bejo.core.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record LoginResponseDto(
        Long id,
        String username,
        String accountType,
        String name,
        String email,
        boolean isEmailVerified,
        String phone,
        boolean isPhoneVerified,
        String businessRole,
        String businessName,
        String businessAddress,
        String nmid,
        // Set once at registration, immutable — only on login (not on /me), so the app caches
        // them from the login response.
        String size,
        String industry,
        String storePhoto,
        String bankName,
        String bankNumber,
        String bankAccountName,
        // Bundled so the client can render its home screen without extra round trips; the
        // individual endpoints (/api/account-settings, /api/business/picture) still exist.
        String profilePicture,
        AccountSettingsResponseDto accountSettings,
        String token,
        String tokenType,
        Long expiresIn
) {}
