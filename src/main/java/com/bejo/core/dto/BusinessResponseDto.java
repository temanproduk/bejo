package com.bejo.core.dto;

// Current business info, returned so the client can repopulate the edit form
// (e.g. after a reset-to-initial). NMID is not here — it belongs to the QRIS now
// and is read from GET /api/qris.
public record BusinessResponseDto(
        String businessName,
        String businessAddress
) {}
