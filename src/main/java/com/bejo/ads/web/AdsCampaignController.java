package com.bejo.ads.web;

import com.bejo.ads.dto.CreateAdCampaignRequestDto;
import com.bejo.ads.dto.AdCampaignResponseDto;
import com.bejo.shared.web.ApiResponseDto;
import com.bejo.ads.service.AdsCampaignService;
import com.bejo.shared.util.Pagination;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Admin/server surface for voice-ad campaigns — create the campaign + audio, tell the target
 * device to prepare (download) and to play, and read campaign state. This is where the brand
 * dashboard will plug in later.
 *
 * <p><b>Auth:</b> these endpoints are exempt from the JWT (they're server-to-server, not a
 * logged-in user); the {@code X-ADS-ADMIN-TOKEN} header is enforced by
 * {@link com.bejo.ads.web.AdsAdminTokenFilter} <em>before</em> the request body is validated. The
 * device-facing reports live on {@code /api/ads/deliveries/**} and use the normal JWT.
 */
@RestController
@RequestMapping("/api/ads/campaigns")
public class AdsCampaignController {

    private final AdsCampaignService adsCampaignService;

    public AdsCampaignController(AdsCampaignService adsCampaignService) {
        this.adsCampaignService = adsCampaignService;
    }

    @PostMapping
    public ResponseEntity<ApiResponseDto<AdCampaignResponseDto>> create(
            @Valid @RequestBody CreateAdCampaignRequestDto request) {
        AdCampaignResponseDto data = adsCampaignService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseDto.success("Kampanye berhasil dibuat", data));
    }

    @PostMapping("/{id}/prepare")
    public ResponseEntity<ApiResponseDto<AdCampaignResponseDto>> prepare(@PathVariable Long id) {
        AdCampaignResponseDto data = adsCampaignService.prepare(id);
        return ResponseEntity.ok(ApiResponseDto.success("Kampanye disiapkan", data));
    }

    @PostMapping("/{id}/play")
    public ResponseEntity<ApiResponseDto<AdCampaignResponseDto>> play(@PathVariable Long id) {
        AdCampaignResponseDto data = adsCampaignService.play(id);
        return ResponseEntity.ok(ApiResponseDto.success("Perintah putar dikirim", data));
    }

    @GetMapping
    public ResponseEntity<ApiResponseDto<List<AdCampaignResponseDto>>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size) {
        Page<AdCampaignResponseDto> data = adsCampaignService.list(Pagination.of(page, size));
        return ResponseEntity.ok(ApiResponseDto.page(data));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponseDto<AdCampaignResponseDto>> get(@PathVariable Long id) {
        return ResponseEntity.ok(ApiResponseDto.success(adsCampaignService.get(id)));
    }
}
