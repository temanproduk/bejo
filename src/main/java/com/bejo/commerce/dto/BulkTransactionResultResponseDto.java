package com.bejo.commerce.dto;

import java.util.List;

/**
 * Per-item outcome of a bulk transaction sync. The client matches each
 * {@link ItemResult} back to its local record by {@code idempotencyKey} to know
 * which rows to mark synced (CREATED/DUPLICATE) and which to retry or fix (FAILED).
 */
public record BulkTransactionResultResponseDto(
        int total,
        int created,
        int duplicate,
        int failed,
        List<ItemResult> results
) {
    /**
     * @param status one of {@code CREATED}, {@code DUPLICATE}, {@code FAILED}
     * @param transactionId the server transaction id (CREATED/DUPLICATE); null when FAILED
     * @param error the failure reason (FAILED); null otherwise
     */
    public record ItemResult(
            String idempotencyKey,
            String status,
            String transactionId,
            String error
    ) {}
}
