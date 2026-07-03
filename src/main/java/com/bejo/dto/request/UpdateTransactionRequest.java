package com.bejo.dto.request;

import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

// Partial update — null fields are left unchanged
public record UpdateTransactionRequest(
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
        String acquirer
) {
    public boolean isEmpty() {
        return transactionDate == null && amount == null && issuerName == null
                && merchantPan == null && customerPan == null && rrn == null && acquirer == null;
    }
}
