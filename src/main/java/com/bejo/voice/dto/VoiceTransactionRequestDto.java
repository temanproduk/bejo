package com.bejo.voice.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * A merchant saying a sale out loud ("jual dua kopi susu sama satu roti, tiga puluh enam ribu, bayar tunai").
 * {@code audio} is base64 of raw 16-bit little-endian mono PCM (no container, no header) recorded at
 * {@code sampleRateHertz} (default 16000); the server wraps it in a WAV header for the model. A hard cap on the
 * encoded length keeps an oversized body from being decoded at all (30 s at 48 kHz is 2.88 MB raw, 3.84 MB as
 * base64); the exact duration limit is checked in the service. {@code products} is the merchant's catalogue as the
 * app has it, at most 200 entries; the spoken product names are matched against it.
 */
public record VoiceTransactionRequestDto(
        @NotBlank(message = "audio wajib diisi")
        @Size(max = 4_000_000, message = "audio terlalu panjang")
        String audio,
        @Min(value = 8000, message = "sampleRateHertz minimal 8000")
        @Max(value = 48000, message = "sampleRateHertz maksimal 48000")
        Integer sampleRateHertz,
        @Valid
        @Size(max = VoiceTransactionRequestDto.MAX_PRODUCTS, message = "products maksimal 200 produk")
        List<VoiceCatalogueProductDto> products
) {
    public static final int MAX_PRODUCTS = 200;
}
