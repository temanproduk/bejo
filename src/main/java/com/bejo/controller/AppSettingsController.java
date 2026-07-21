package com.bejo.controller;

import com.bejo.dto.request.UpdateInventoryEnabledRequestDto;
import com.bejo.dto.request.UpdateKeywordsRequestDto;
import com.bejo.dto.request.UpdateMonitoredAppsRequestDto;
import com.bejo.dto.request.UpdateQrisWithProfilePictureRequestDto;
import com.bejo.dto.request.UpdateReceiveAdsRequestDto;
import com.bejo.dto.response.ApiResponseDto;
import com.bejo.dto.response.AppSettingsResponseDto;
import com.bejo.service.AppSettingsService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/app-settings")
public class AppSettingsController {

    private final AppSettingsService appSettingsService;

    public AppSettingsController(AppSettingsService appSettingsService) {
        this.appSettingsService = appSettingsService;
    }

    @GetMapping
    public ResponseEntity<ApiResponseDto<AppSettingsResponseDto>> getSettings(Authentication authentication) {
        AppSettingsResponseDto data = appSettingsService.getSettings(authentication.getName());
        return ResponseEntity.ok(ApiResponseDto.success("Pengaturan berhasil dimuat", data));
    }

    @PutMapping("/monitored-apps")
    public ResponseEntity<ApiResponseDto<AppSettingsResponseDto>> updateMonitoredApps(
            @Valid @RequestBody UpdateMonitoredAppsRequestDto request,
            Authentication authentication) {
        AppSettingsResponseDto data = appSettingsService.updateMonitoredApps(authentication.getName(), request);
        return ResponseEntity.ok(ApiResponseDto.success("Aplikasi terpantau disimpan", data));
    }

    @PutMapping("/keywords")
    public ResponseEntity<ApiResponseDto<AppSettingsResponseDto>> updateKeywords(
            @Valid @RequestBody UpdateKeywordsRequestDto request,
            Authentication authentication) {
        AppSettingsResponseDto data = appSettingsService.updateKeywords(authentication.getName(), request);
        return ResponseEntity.ok(ApiResponseDto.success("Kata kunci notifikasi disimpan", data));
    }

    @PutMapping("/qris-with-profile-picture")
    public ResponseEntity<ApiResponseDto<AppSettingsResponseDto>> updateQrisWithProfilePicture(
            @Valid @RequestBody UpdateQrisWithProfilePictureRequestDto request,
            Authentication authentication) {
        AppSettingsResponseDto data = appSettingsService.updateQrisWithProfilePicture(authentication.getName(), request.enabled());
        return ResponseEntity.ok(ApiResponseDto.success("Pengaturan QRIS disimpan", data));
    }

    @PutMapping("/inventory")
    public ResponseEntity<ApiResponseDto<AppSettingsResponseDto>> updateInventoryEnabled(
            @Valid @RequestBody UpdateInventoryEnabledRequestDto request,
            Authentication authentication) {
        AppSettingsResponseDto data = appSettingsService.updateInventoryEnabled(authentication.getName(), request.enabled());
        return ResponseEntity.ok(ApiResponseDto.success("Pengaturan inventaris disimpan", data));
    }

    // Whether the business wants to receive voice ads (super admin only, via loadOrCreate).
    @PutMapping("/receive-ads")
    public ResponseEntity<ApiResponseDto<AppSettingsResponseDto>> updateReceiveAds(
            @Valid @RequestBody UpdateReceiveAdsRequestDto request,
            Authentication authentication) {
        AppSettingsResponseDto data = appSettingsService.updateReceiveAds(authentication.getName(), request.enabled());
        return ResponseEntity.ok(ApiResponseDto.success("Pengaturan iklan disimpan", data));
    }
}
