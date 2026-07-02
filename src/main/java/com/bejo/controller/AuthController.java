package com.bejo.controller;

import com.bejo.dto.request.LoginRequest;
import com.bejo.dto.request.RegisterRequest;
import com.bejo.dto.response.ApiResponse;
import com.bejo.dto.response.LoginData;
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
    public ResponseEntity<ApiResponse<Void>> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success("Registrasi berhasil", null));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginData>> login(@Valid @RequestBody LoginRequest request) {
        LoginData data = authService.login(request);
        return ResponseEntity.ok(ApiResponse.success("session data is created", data));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(HttpServletRequest request) {
        String authHeader = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return ResponseEntity.badRequest()
                    .body(ApiResponse.error("Authorization header tidak valid"));
        }
        authService.logout(authHeader.substring(7));
        return ResponseEntity.ok(ApiResponse.success("Logged out successfully", null));
    }
}
