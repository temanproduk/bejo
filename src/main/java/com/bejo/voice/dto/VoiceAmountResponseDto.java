package com.bejo.voice.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * What was heard and the amount it came to. {@code amount} is absent when speech was heard but held no usable
 * number (the app then asks the merchant to try again or type it); {@code transcript} and {@code confidence}
 * are absent when nothing was heard at all.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VoiceAmountResponseDto(String transcript, Long amount, Double confidence) {}
