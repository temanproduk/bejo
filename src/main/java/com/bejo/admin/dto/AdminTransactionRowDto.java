package com.bejo.admin.dto;

import java.time.LocalDateTime;

/** One row of the dashboard's transaction list, with the owning business's name. */
public record AdminTransactionRowDto(
        String transactionId,
        LocalDateTime transactionDate,
        Long amount,
        String type,
        String status,
        String acquirer,
        String rrn,
        Long businessId,
        String businessName,
        String createdBy,
        // Non-null = soft-deleted by the merchant
        LocalDateTime deletedAt
) {}
