package com.bejo.controller;

import com.bejo.dto.request.LoginRequestDto;
import com.bejo.dto.request.RegisterRequestDto;
import com.bejo.dto.response.ApiResponseDto;
import com.bejo.dto.response.LoginResponseDto;
import com.bejo.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;



@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponseDto<Void>> register(@Valid @RequestBody RegisterRequestDto request) {
        authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseDto.success("Registrasi berhasil", null));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponseDto<LoginResponseDto>> login(@Valid @RequestBody LoginRequestDto request) {
        LoginResponseDto data = authService.login(request);
        return ResponseEntity.ok(ApiResponseDto.success("session data is created", data));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponseDto<Void>> logout(HttpServletRequest request) {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.badRequest()
                    .body(ApiResponseDto.error("Authorization header tidak valid"));
        }
        authService.logout(authHeader.substring(7));
        return ResponseEntity.ok(ApiResponseDto.success("Logged out successfully", null));
    }
}
