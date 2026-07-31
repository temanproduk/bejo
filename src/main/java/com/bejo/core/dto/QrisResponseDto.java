package com.bejo.core.dto;

/**
 * The caller's effective QRIS. {@code qrisCode} is the EMV payload string — the app renders the
 * QR image from it, so no image crosses the wire.
 *
 * <p>Two names travel together deliberately: {@code businessName} is what the merchant typed into
 * business info, while {@code qrisMerchantName} and {@code nmid} are parsed from this very code.
 * The app captions the QR with the latter pair so the caption always matches the QR on screen —
 * a sub-account with its own QRIS shows its own merchant identity, not the business's.
 */
public record QrisResponseDto(
        String type,
        String qrisCode,
        String qrisMerchantName,
        String nmid,
        // The acquirer's GUID from the code, e.g. "ID.CO.ASTRAPAY.WWW"
        String acquirer,
        String businessName,
        String businessAddress
) {}
