package com.bejo.dto.request;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * SNAP-like QR MPM payment notification (callback) from the QRIS merchant service.
 * Unknown fields are ignored so the contract tolerates payload additions.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QrMpmNotifyRequest(
        @NotBlank(message = "originalReferenceNo wajib diisi")
        String originalReferenceNo,

        @NotBlank(message = "originalPartnerReferenceNo wajib diisi")
        String originalPartnerReferenceNo,

        @NotBlank(message = "latestTransactionStatus wajib diisi")
        String latestTransactionStatus,

        String transactionStatusDesc,
        String customerNumber,
        String destinationNumber,
        String destinationAccountName,
        String externalStoreId,

        @NotNull(message = "amount wajib diisi")
        @Valid
        Amount amount,

        AdditionalInfo additionalInfo
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Amount(
            @NotBlank(message = "amount.value wajib diisi")
            String value,
            String currency
    ) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AdditionalInfo(
            String tip,
            String deviceId,
            String channel,
            String gracePeriod,
            String terminalId,
            String userName,
            String merchantPan,
            String customerPan,
            String issuerName,
            String transactionId
    ) {}
}
