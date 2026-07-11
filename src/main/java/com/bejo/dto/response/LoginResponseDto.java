package com.bejo.dto.response;

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
        String bankName,
        String bankNumber,
        String bankAccountName,
        // Bundled so the client can render its home screen without extra round trips; the
        // individual endpoints (/api/app-settings, /api/business/picture) still exist.
        String profilePicture,
        AppSettingsResponseDto appSettings,
        String token,
        String tokenType,
        Long expiresIn
) {}
