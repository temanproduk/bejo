package com.bejo.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public record NotificationTransactionRequestDto(
        @NotBlank(message = "transactionId wajib diisi")
        @Size(max = 255, message = "transactionId maksimal 255 karakter")
        String transactionId,

        @NotNull(message = "transactionDate wajib diisi")
        LocalDateTime transactionDate,

        @NotNull(message = "amount wajib diisi")
        @Positive(message = "amount harus lebih dari 0")
        Long amount,

        @NotBlank(message = "issuerName wajib diisi")
        @Size(max = 255, message = "issuerName maksimal 255 karakter")
        String issuerName,

        @Size(max = 50, message = "merchantPan maksimal 50 karakter")
        String merchantPan,

        @Size(max = 50, message = "customerPan maksimal 50 karakter")
        String customerPan,

        @Size(max = 255, message = "rrn maksimal 255 karakter")
        String rrn
) {}
