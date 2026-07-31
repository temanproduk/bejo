package com.bejo.core.dto;

/**
 * The caller's effective QRIS — their own if the super admin assigned them one, else the shared
 * business QRIS.
 *
 * <p>{@code qrisCode} is the EMV payload; the app renders the QR from it, so no image crosses the
 * wire. {@code merchantName}, {@code nmid} and {@code acquirer} are parsed from that same code, so
 * the caption always describes the QR on screen — caption with these, not with
 * {@code businessName}, which is the separately-typed business info.
 */
public record QrisResponseDto(
        String qrisCode,
        String merchantName,
        String nmid,
        String acquirer,
        String terminalId,
        String businessName,
        String businessAddress
) {}
