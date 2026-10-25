package com.bejo.voice.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;

import java.util.List;

/**
 * The first frame the app sends on the live "say the sale" WebSocket: the merchant's product list, as in
 * {@link VoiceTransactionRequestDto#products()} (at most 200 entries; the spoken names are matched against it). The recording
 * itself follows as binary frames of 16 kHz mono 16-bit little-endian PCM.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record VoiceLiveStartDto(
        @Valid
        @Size(max = VoiceTransactionRequestDto.MAX_PRODUCTS, message = "products maksimal 200 produk")
        List<VoiceCatalogueProductDto> products
) {}
