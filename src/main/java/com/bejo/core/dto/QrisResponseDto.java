package com.bejo.core.dto;

/**
 * The caller's effective QRIS. {@code qrisCode} is the EMV payload string — the app renders the
 * QR image from it, so no image crosses the wire.
 */
public record QrisResponseDto(
        String type,
        String qrisCode,
        String businessName,
        String businessAddress,
        String nmid
) {}
