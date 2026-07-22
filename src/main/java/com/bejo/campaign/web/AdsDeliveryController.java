package com.bejo.campaign.web;

import com.bejo.campaign.dto.AdDownloadReportRequestDto;
import com.bejo.campaign.dto.AdLocationReportRequestDto;
import com.bejo.campaign.dto.AdPlayedReportRequestDto;
import com.bejo.campaign.dto.AdDeliveryResponseDto;
import com.bejo.shared.web.ApiResponseDto;
import com.bejo.campaign.service.AdsCampaignService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * Device-facing reports for a voice-ad delivery — the app calls these (JWT-authenticated) as it
 * works through the campaign it was pushed: it downloaded the audio, it played it, and (when it
 * finally can read location in the foreground) where. The {@code deliveryId} comes from the push;
 * the service checks the caller is that delivery's target.
 */
@RestController
@RequestMapping("/api/ads/deliveries")
public class AdsDeliveryController {

    private final AdsCampaignService adsCampaignService;

    public AdsDeliveryController(AdsCampaignService adsCampaignService) {
        this.adsCampaignService = adsCampaignService;
    }

    // Step 3/4: the app finished downloading — report integrity so the server acks READY or ERROR
    @PostMapping("/{id}/downloaded")
    public ResponseEntity<ApiResponseDto<AdDeliveryResponseDto>> downloaded(
            @PathVariable Long id,
            @Valid @RequestBody AdDownloadReportRequestDto request,
            Authentication authentication) {
        AdDeliveryResponseDto data = adsCampaignService.reportDownloaded(authentication.getName(), id, request);
        return ResponseEntity.ok(ApiResponseDto.success("Laporan unduhan diterima", data));
    }

    // Step 7: the app played the ad (with location if it had it)
    @PostMapping("/{id}/played")
    public ResponseEntity<ApiResponseDto<AdDeliveryResponseDto>> played(
            @PathVariable Long id,
            @Valid @RequestBody AdPlayedReportRequestDto request,
            Authentication authentication) {
        AdDeliveryResponseDto data = adsCampaignService.reportPlayed(authentication.getName(), id, request);
        return ResponseEntity.ok(ApiResponseDto.success("Laporan putar diterima", data));
    }

    // Step 8: the deferred play location, once the app can read it in the foreground
    @PostMapping("/{id}/location")
    public ResponseEntity<ApiResponseDto<AdDeliveryResponseDto>> location(
            @PathVariable Long id,
            @Valid @RequestBody AdLocationReportRequestDto request,
            Authentication authentication) {
        AdDeliveryResponseDto data = adsCampaignService.reportLocation(authentication.getName(), id, request);
        return ResponseEntity.ok(ApiResponseDto.success("Lokasi putar diterima", data));
    }
}
