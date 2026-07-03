package com.bejo.dto.response;

// Current bank info, returned so the client can repopulate the edit form
// (e.g. after a reset-to-initial).
public record BankResponseDto(
        String bankName,
        String bankNumber,
        String bankAccountName
) {}
