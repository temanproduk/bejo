package com.bejo.dto.response;

public record LoginData(
        String username,
        String accountType,
        String name,
        String email,
        boolean isEmailVerified,
        String phone,
        boolean isPhoneVerified,
        String businessName,
        String businessAddress,
        String bankName,
        String bankNumber,
        String bankAccountName,
        String token,
        String tokenType
) {}
