package com.bejo.core.web;

import com.bejo.core.dto.ForgotPasswordRequestDto;
import com.bejo.core.dto.ResetPasswordRequestDto;
import com.bejo.core.service.PasswordResetService;
import com.bejo.shared.web.ApiResponseDto;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Forgotten-password flow for the app. Both endpoints are public (no login — that is the point) but
 * still need {@code X-API-TOKEN}, like login. See {@link PasswordResetService} for the security design.
 */
@RestController
@RequestMapping("/api/auth")
public class PasswordResetController {

    private final PasswordResetService passwordResetService;

    public PasswordResetController(PasswordResetService passwordResetService) {
        this.passwordResetService = passwordResetService;
    }

    /** Always the same answer for an existing and a non-existing username. */
    @PostMapping("/forgot-password")
    public ResponseEntity<ApiResponseDto<Void>> forgotPassword(@Valid @RequestBody ForgotPasswordRequestDto request) {
        passwordResetService.requestReset(request.username());
        return ResponseEntity.ok(ApiResponseDto.success(
                "Jika akun terdaftar, kode reset telah dikirim ke email Anda", null));
    }

    @PostMapping("/reset-password")
    public ResponseEntity<ApiResponseDto<Void>> resetPassword(@Valid @RequestBody ResetPasswordRequestDto request) {
        passwordResetService.resetPassword(request.username(), request.code(), request.newPassword());
        return ResponseEntity.ok(ApiResponseDto.success(
                "Kata sandi berhasil diubah. Silakan masuk dengan kata sandi baru", null));
    }
}
