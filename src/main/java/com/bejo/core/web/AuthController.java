package com.bejo.core.web;

import com.bejo.core.dto.LoginRequestDto;
import com.bejo.core.dto.LogoutRequestDto;
import com.bejo.shared.web.ApiResponseDto;
import com.bejo.core.dto.LoginResponseDto;
import com.bejo.core.service.AuthService;
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
        return ResponseEntity.ok(ApiResponseDto.success("Berhasil masuk", data));
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
        return ResponseEntity.ok(ApiResponseDto.success("Berhasil keluar", null));
    }
}
