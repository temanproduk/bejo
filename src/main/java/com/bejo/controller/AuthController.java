package com.bejo.controller;

import com.bejo.dto.request.LoginRequestDto;
import com.bejo.dto.request.LogoutRequestDto;
import com.bejo.dto.response.ApiResponseDto;
import com.bejo.dto.response.LoginResponseDto;
import com.bejo.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;



@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponseDto<LoginResponseDto>> login(@Valid @RequestBody LoginRequestDto request) {
        LoginResponseDto data = authService.login(request);
        return ResponseEntity.ok(ApiResponseDto.success("session data is created", data));
    }

    // Optional body { "deviceId": "..." } — when present, the device's FCM token is
    // cleared so it stops receiving pushes after logout.
    @PostMapping("/logout")
    public ResponseEntity<ApiResponseDto<Void>> logout(
            HttpServletRequest request,
            @Valid @RequestBody(required = false) LogoutRequestDto body,
            Authentication authentication) {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.badRequest()
                    .body(ApiResponseDto.error("Authorization header tidak valid"));
        }
        String deviceId = body != null ? body.deviceId() : null;
        authService.logout(authHeader.substring(7), authentication.getName(), deviceId);
        return ResponseEntity.ok(ApiResponseDto.success("Logged out successfully", null));
    }
}
