package com.bejo.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionResponseDto(
        String transactionId,
        LocalDateTime transactionDate,
        Long amount,
        String type,
        String acquirer,
        String issuerName,
        String merchantName,
        String merchantPan,
        String customerPan,
        String rrn
) {}
