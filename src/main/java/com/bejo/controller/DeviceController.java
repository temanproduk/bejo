package com.bejo.controller;

import com.bejo.dto.request.UpdateTokenRequest;
import com.bejo.dto.response.ApiResponse;
import com.bejo.dto.response.UpdateTokenData;
import com.bejo.service.DeviceService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    private final DeviceService deviceService;

    public DeviceController(DeviceService deviceService) {
        this.deviceService = deviceService;
    }

    @PutMapping("/{deviceId}/fcm-token")
    public ResponseEntity<ApiResponse<UpdateTokenData>> updateFcmToken(
            @PathVariable String deviceId,
            @Valid @RequestBody UpdateTokenRequest request) {
        UpdateTokenData data = deviceService.updateFcmToken(deviceId, request.pushToken());
        return ResponseEntity.ok(ApiResponse.success("Push Token data is updated", data));
    }
}
