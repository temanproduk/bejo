package com.bejo.core.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST /api/qris/upload} — the business's shared QRIS.
 *
 * <p>Carries the <b>QRIS code</b> (the EMV payload the app decodes from the merchant's QR), not an
 * image: the app renders the QR locally, so a few hundred bytes travel instead of a multi-MB
 * base64 PNG.
 */
public record UploadQrisRequestDto(
        @NotBlank(message = "qrisCode wajib diisi")
        @Size(max = 1024, message = "qrisCode terlalu panjang (maksimal 1024 karakter)")
        String qrisCode
) {}
