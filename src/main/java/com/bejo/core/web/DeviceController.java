package com.bejo.core.web;

import com.bejo.core.dto.UpdateTokenRequestDto;
import com.bejo.shared.web.ApiResponseDto;
import com.bejo.core.dto.UpdateTokenResponseDto;
import com.bejo.core.service.DeviceService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @PutMapping("/{deviceId}/fcm-token")
    public ResponseEntity<ApiResponseDto<UpdateTokenResponseDto>> updateFcmToken(
            @PathVariable String deviceId,
            @Valid @RequestBody UpdateTokenRequestDto request,
            Authentication authentication) {
        UpdateTokenResponseDto data = deviceService.updateFcmToken(authentication.getName(), deviceId, request.pushToken());
        return ResponseEntity.ok(ApiResponseDto.success("Token notifikasi diperbarui", data));
    }
}
