package com.bejo.commerce.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransactionResponseDto(
        String transactionId,
        LocalDateTime transactionDate,
        Long amount,
        String type,
        // SUCCESS = paid; AWAITING_PAYMENT = a QRIS sale not paid yet (not counted as income);
        // the rest come from the QRIS callback (PENDING, FAILED, REFUNDED, ...)
        String status,
        // When an awaiting-payment transaction was paid; absent for one that never waited
        LocalDateTime paidAt,
        // Only on a sale that is AWAITING_PAYMENT: when it will be cancelled automatically if still unpaid
        LocalDateTime autoCancelAt,
        String acquirer,
        String issuerName,
        String merchantName,
        String merchantPan,
        String customerPan,
        String rrn,
        // Who created / last edited this transaction (username of the account or sub-account).
        // MANUAL/PARSED only; both null on a MANAGED callback row. updatedBy is null until edited.
        String createdBy,
        String updatedBy,
        // Products sold in this transaction. Only populated on the single-transaction
        // detail (GET /api/transactions/{id}); omitted (null) from the history list.
        List<TransactionItemResponseDto> items
) {}
