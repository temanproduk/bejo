package com.bejo.commerce.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * SNAP-like QR MPM payment notification (callback) from the QRIS merchant service.
 * Unknown fields are ignored so the contract tolerates payload additions.
 *
 * <p><b>PoC note:</b> the provider's callback is currently buggy and only sends a
 * valid amount + status (reference numbers, merchantPan, transactionId all null), so
 * validation is intentionally lenient — every field is optional. The service records
 * the payment against the seed user regardless. Tighten this back up once the provider
 * fixes the payload.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record QrMpmNotifyRequestDto(
        String originalReferenceNo,
        String originalPartnerReferenceNo,
        String latestTransactionStatus,
        String transactionStatusDesc,
        String customerNumber,
        String destinationNumber,
        String destinationAccountName,
        String externalStoreId,
        Amount amount,
        AdditionalInfo additionalInfo
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Amount(
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
