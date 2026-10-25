package com.bejo.voice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * A frame carrying the sale on the live "say the sale" WebSocket: {@code type} is {@code draft} (what has been understood so
 * far — always the whole sale, never a delta) or {@code done} (the final one). The fields after it are exactly those of
 * {@link VoiceTransactionResponseDto}, so the app reads a live answer the way it reads the one-shot one. Nulls are written out.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record VoiceLiveSaleDto(
        String type,
        boolean understood,
        String transcript,
        Long amount,
        String paymentMethod,
        String customerInfo,
        String merchantInfo,
        List<VoiceTransactionItemDto> items
) {
    public static VoiceLiveSaleDto of(String type, VoiceTransactionResponseDto sale) {
        return new VoiceLiveSaleDto(type, sale.understood(), sale.transcript(), sale.amount(), sale.paymentMethod(),
                sale.customerInfo(), sale.merchantInfo(), sale.items());
    }
}
