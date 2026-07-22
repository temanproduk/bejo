package com.bejo.core.web;

import com.bejo.core.dto.UpdateKeywordsRequestDto;
import com.bejo.core.dto.UpdateMonitoredAppsRequestDto;
import com.bejo.core.dto.UpdateSettingToggleRequestDto;
import com.bejo.core.dto.AccountSettingsResponseDto;
import com.bejo.shared.web.ApiResponseDto;
import com.bejo.core.service.AccountSettingsService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/account-settings")
public class AccountSettingsController {

    private final AccountSettingsService accountSettingsService;

    public AccountSettingsController(AccountSettingsService accountSettingsService) {
        this.accountSettingsService = accountSettingsService;
    }

    @GetMapping
    public ResponseEntity<ApiResponseDto<AccountSettingsResponseDto>> getSettings(Authentication authentication) {
        AccountSettingsResponseDto data = accountSettingsService.getSettings(authentication.getName());
        return ResponseEntity.ok(ApiResponseDto.success("Pengaturan berhasil dimuat", data));
    }

    @PutMapping("/monitored-apps")
    public ResponseEntity<ApiResponseDto<AccountSettingsResponseDto>> updateMonitoredApps(
            @Valid @RequestBody UpdateMonitoredAppsRequestDto request,
            Authentication authentication) {
        AccountSettingsResponseDto data = accountSettingsService.updateMonitoredApps(authentication.getName(), request);
        return ResponseEntity.ok(ApiResponseDto.success("Aplikasi terpantau disimpan", data));
    }

    @PutMapping("/keywords")
    public ResponseEntity<ApiResponseDto<AccountSettingsResponseDto>> updateKeywords(
            @Valid @RequestBody UpdateKeywordsRequestDto request,
            Authentication authentication) {
        AccountSettingsResponseDto data = accountSettingsService.updateKeywords(authentication.getName(), request);
        return ResponseEntity.ok(ApiResponseDto.success("Kata kunci notifikasi disimpan", data));
    }

    // Generic on/off for any boolean setting (qrisWithProfilePicture | inventory | receiveAds |
    // teamManagement). Super admin only; an unknown setting is a 400.
    @PutMapping("/toggle")
    public ResponseEntity<ApiResponseDto<AccountSettingsResponseDto>> toggle(
            @Valid @RequestBody UpdateSettingToggleRequestDto request,
            Authentication authentication) {
        AccountSettingsResponseDto data = accountSettingsService.toggle(
                authentication.getName(), request.setting(), request.enabled());
        return ResponseEntity.ok(ApiResponseDto.success("Pengaturan disimpan", data));
    }
}
