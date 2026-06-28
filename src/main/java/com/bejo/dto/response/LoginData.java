package com.bejo.dto.response;

public record LoginData(
        String username,
        String name,
        String email,
        String phone,
        String businessName,
        String businessAddress,
        String bankName,
        String bankNumber,
        String bankAccountName,
        String token,
        String tokenType
) {}
