package com.bejo.campaign.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.LocalDateTime;
import java.util.List;

/**
 * A campaign with its delivery/deliveries. {@code state} is a convenience mirror of the single
 * delivery's state (today a campaign has exactly one) so the client can read the lifecycle
 * without digging into the array; it's null when a campaign has more than one delivery, where
 * the {@code deliveries} list is authoritative.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AdCampaignResponseDto(
        Long id,
        String name,
        String brand,
        String audioContentType,
        String audioSha256,
        Long audioSizeBytes,
        String state,
        LocalDateTime createdAt,
        List<AdDeliveryResponseDto> deliveries
) {}
