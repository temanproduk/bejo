package com.bejo.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Acknowledgment returned to the QRIS merchant service. This is the callback
 * contract's own shape — deliberately not the internal ApiResponse envelope.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record QrMpmNotifyResponse(
        Long id,
        String billNumber,
        String referenceNumber,
        BigDecimal amount,
        BigDecimal tip,
        String status,
        LocalDateTime createdAt
) {}
