package com.bejo.dto.response;

import java.time.LocalDateTime;

/**
 * One row of a merchant's ad history: a delivery to one of the business's devices, flattened with
 * the campaign's name/brand so the app needs no second lookup. Merchant-facing (the super admin
 * reads the whole business's history), so it carries only what a merchant cares about — which ad,
 * to whom, its status, and when it played — not the internal integrity/step fields the delivery
 * lifecycle uses.
 *
 * <p>The target user is identified by both {@code targetUsername} and {@code targetName} (both
 * snapshotted on the delivery, so they read correctly even after the member is removed/renamed);
 * {@code userActive} is resolved on read and is false once that member has left the business.
 */
public record AdHistoryResponseDto(
        Long id,
        Long campaignId,
        String campaignName,
        String brand,
        String targetUsername,
        String targetName,
        boolean userActive,
        String state,
        String errorReason,
        LocalDateTime playedAt,
        LocalDateTime finishedAt,
        Double playLatitude,
        Double playLongitude,
        LocalDateTime createdAt
) {}
