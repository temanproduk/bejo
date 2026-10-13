package com.bejo.admin.web;

import com.bejo.admin.dto.AdminLoginResponseDto;
import com.bejo.admin.dto.AdminOtpRequestDto;
import com.bejo.admin.dto.AdminOtpVerifyRequestDto;
import com.bejo.admin.service.AdminAuthService;
import com.bejo.shared.web.ApiResponseDto;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Dashboard sign-in by emailed one-time code. {@code otp/request} and {@code otp/verify} are open
 * (apart from {@code X-API-TOKEN}); the rest need the admin session — see {@link AdminAuthFilter}
 * and {@link AdminAuthService}.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminAuthController {

    private final AdminAuthService authService;

    public AdminAuthController(AdminAuthService authService) {
        this.authService = authService;
    }

    /** Always the same answer, whether or not the address is an admin. */
    @PostMapping("/auth/otp/request")
    public ResponseEntity<ApiResponseDto<Void>> requestCode(@Valid @RequestBody AdminOtpRequestDto request) {
        authService.requestCode(request.email());
        return ResponseEntity.ok(ApiResponseDto.success(
                "Jika alamat ini terdaftar sebagai admin, kode masuk telah dikirim", null));
    }

    @PostMapping("/auth/otp/verify")
    public ResponseEntity<ApiResponseDto<AdminLoginResponseDto>> verifyCode(
            @Valid @RequestBody AdminOtpVerifyRequestDto request) {
        return ResponseEntity.ok(ApiResponseDto.success(authService.verifyCode(request.email(), request.code())));
    }

    @PostMapping("/auth/logout")
    public ResponseEntity<ApiResponseDto<Void>> logout(@RequestAttribute(AdminAuthFilter.ADMIN_TOKEN) String token) {
        authService.signOut(token);
        return ResponseEntity.ok(ApiResponseDto.success("Berhasil keluar", null));
    }

    @GetMapping("/me")
    public ResponseEntity<ApiResponseDto<Map<String, String>>> me(
            @RequestAttribute(AdminAuthFilter.ADMIN_EMAIL) String email) {
        return ResponseEntity.ok(ApiResponseDto.success(Map.of("email", email)));
    }
}
