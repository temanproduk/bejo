package com.bejo.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionDto(
        String transactionId,
        LocalDateTime transactionDate,
        BigDecimal amount,
        String issuerName,
        String merchantName,
        String merchantPan,
        String customerPan,
        String rrn
) {}
