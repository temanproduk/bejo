package com.bejo.dto.response;

import java.time.LocalDateTime;

/**
 * One row of a merchant's ad history: a delivery to one of the business's devices, flattened with
 * the campaign's name/brand so the app needs no second lookup. Merchant-facing (the super admin
 * reads the whole business's history), so it carries only what a merchant cares about — which ad,
 * to whom, its status, and when it played — not the internal integrity/step fields the delivery
 * lifecycle uses.
 */
public record AdHistoryResponseDto(
        Long id,
        Long campaignId,
        String campaignName,
        String brand,
        String targetUsername,
        String state,
        String errorReason,
        LocalDateTime playedAt,
        LocalDateTime finishedAt,
        Double playLatitude,
        Double playLongitude,
        LocalDateTime createdAt
) {}
