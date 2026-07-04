package com.bejo.controller;

import com.bejo.dto.request.UpdateKeywordsRequestDto;
import com.bejo.dto.request.UpdateMonitoredAppsRequestDto;
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
        return ResponseEntity.ok(ApiResponseDto.success("App settings retrieved", data));
    }

    @PutMapping("/monitored-apps")
    public ResponseEntity<ApiResponseDto<AppSettingsResponseDto>> updateMonitoredApps(
            @Valid @RequestBody UpdateMonitoredAppsRequestDto request,
            Authentication authentication) {
        AppSettingsResponseDto data = appSettingsService.updateMonitoredApps(authentication.getName(), request);
        return ResponseEntity.ok(ApiResponseDto.success("Daftar aplikasi dipantau berhasil disimpan", data));
    }

    @PutMapping("/keywords")
    public ResponseEntity<ApiResponseDto<AppSettingsResponseDto>> updateKeywords(
            @Valid @RequestBody UpdateKeywordsRequestDto request,
            Authentication authentication) {
        AppSettingsResponseDto data = appSettingsService.updateKeywords(authentication.getName(), request);
        return ResponseEntity.ok(ApiResponseDto.success("Kata kunci notifikasi berhasil disimpan", data));
    }
}
