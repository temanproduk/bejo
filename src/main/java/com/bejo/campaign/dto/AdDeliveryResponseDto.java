package com.bejo.campaign.dto;

import java.time.LocalDateTime;

/** One delivery of a campaign to one device, with its lifecycle state + per-step timestamps. */
public record AdDeliveryResponseDto(
        Long id,
        Long campaignId,
        String targetUsername,
        String targetDeviceId,
        String state,
        String errorReason,
        String reportedSha256,
        Long reportedSizeBytes,
        LocalDateTime preparedAt,
        LocalDateTime readyAt,
        LocalDateTime startedAt,
        LocalDateTime playedAt,
        LocalDateTime finishedAt,
        LocalDateTime erroredAt,
        Double playLatitude,
        Double playLongitude,
        LocalDateTime createdAt
) {}
