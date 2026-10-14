package com.bejo.voice.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * A few seconds of the merchant saying an amount. {@code audio} is base64 of raw 16-bit little-endian mono
 * PCM (no container, no header) — what Android's {@code AudioRecord} hands over — recorded at
 * {@code sampleRateHertz} (default 16000). A hard cap on the encoded length keeps an oversized body from
 * being decoded at all; the exact duration limit is checked in the service.
 */
public record VoiceAmountRequestDto(
        @NotBlank(message = "audio wajib diisi")
        @Size(max = 700_000, message = "audio terlalu panjang")
        String audio,
        @Min(value = 8000, message = "sampleRateHertz minimal 8000")
        @Max(value = 48000, message = "sampleRateHertz maksimal 48000")
        Integer sampleRateHertz
) {}
