package com.bejo.dto.response;

// Current business info, returned so the client can repopulate the edit form
// (e.g. after a reset-to-initial).
public record BusinessResponseDto(
        String businessName,
        String businessAddress,
        String nmid
) {}
