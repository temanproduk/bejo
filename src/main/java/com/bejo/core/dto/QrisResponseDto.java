package com.bejo.core.dto;

/**
 * The caller's effective QRIS. {@code qrisCode} is the EMV payload string — the app renders the
 * QR image from it, so no image crosses the wire.
 *
 * <p>{@code legacyQrisImage} is only populated for merchants onboarded before the switch, whose
 * stored value is still a base64 image the server cannot decode into a code. It is null for
 * everyone else and disappears once such a merchant re-uploads.
 */
public record QrisResponseDto(
        String type,
        String qrisCode,
        String legacyQrisImage,
        String businessName,
        String businessAddress,
        String nmid
) {}
