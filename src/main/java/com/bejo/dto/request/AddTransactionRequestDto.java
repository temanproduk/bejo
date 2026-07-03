package com.bejo.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.time.LocalDateTime;

public record AddTransactionRequestDto(
        @NotBlank(message = "type wajib diisi")
        @Pattern(regexp = "^(MANUAL|PARSED)$", message = "type harus MANUAL atau PARSED")
        String type,

        @NotNull(message = "transactionDate wajib diisi")
        LocalDateTime transactionDate,

        @NotNull(message = "amount wajib diisi")
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

        // Required when type = PARSED; validated in service (cross-field rule)
        @Size(max = 255, message = "acquirer maksimal 255 karakter")
        String acquirer
) {}
