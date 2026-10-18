package com.bejo.voice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * What the speech model understood of the sale, after the service has validated every field (the model's output is
 * treated as untrusted input). {@code understood} is true when an amount or at least one item was found. Nulls are
 * written out ({@code ALWAYS}) so each field is always present, as the app's contract lists them.
 */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record VoiceTransactionResponseDto(
        boolean understood,
        String transcript,
        Long amount,
        String paymentMethod,
        String customerInfo,
        String merchantInfo,
        List<VoiceTransactionItemDto> items
) {}
