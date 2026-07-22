package com.bejo.campaign.web;

import com.bejo.campaign.dto.AdHistoryResponseDto;
import com.bejo.shared.web.ApiResponseDto;
import com.bejo.campaign.service.AdsCampaignService;
import com.bejo.shared.util.Pagination;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Merchant-facing ad history — the business's super admin reads which ads were delivered to its
 * devices (itself + its sub-accounts) and how each played out. Unlike the campaign admin surface
 * ({@code /api/ads/campaigns/**}, gated by the ads admin token), this is a normal JWT endpoint:
 * it falls through to {@code authenticated()} and the service enforces super-admin scope.
 */
@RestController
@RequestMapping("/api/ads/history")
public class AdsHistoryController {

    private final AdsCampaignService adsCampaignService;

    public AdsHistoryController(AdsCampaignService adsCampaignService) {
        this.adsCampaignService = adsCampaignService;
    }

    @GetMapping
    public ResponseEntity<ApiResponseDto<List<AdHistoryResponseDto>>> history(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            Authentication authentication) {
        Page<AdHistoryResponseDto> data =
                adsCampaignService.history(authentication.getName(), Pagination.of(page, size));
        return ResponseEntity.ok(ApiResponseDto.page(data));
    }
}
