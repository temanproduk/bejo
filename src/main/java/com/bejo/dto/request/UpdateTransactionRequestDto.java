package com.bejo.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;
import java.util.List;

// Partial update — null fields are left unchanged
public record UpdateTransactionRequestDto(
        LocalDateTime transactionDate,

        @Positive(message = "amount harus lebih dari 0")
        Long amount,

        @Size(max = 255, message = "issuerName maksimal 255 karakter")
        String issuerName,

        @Size(max = 50, message = "merchantPan maksimal 50 karakter")
        String merchantPan,

        @Size(max = 50, message = "customerPan maksimal 50 karakter")
        String customerPan,

        @Size(max = 255, message = "rrn maksimal 255 karakter")
        String rrn,

        @Size(max = 255, message = "acquirer maksimal 255 karakter")
        String acquirer,

        // Full REPLACEMENT of the sold-product line items (not a partial merge). Non-null
        // replaces the items and reconciles stock by delta (returns stock for removed/reduced
        // lines, takes it for added/increased lines) + re-derives amount. An empty list clears
        // all items (restores their stock); null leaves the items unchanged.
        @Valid
        @Size(max = 200, message = "items maksimal 200 baris")
        List<TransactionItemRequestDto> items
) {
    public boolean isEmpty() {
        return transactionDate == null && amount == null && issuerName == null
                && merchantPan == null && customerPan == null && rrn == null && acquirer == null
                && items == null;
    }
}
