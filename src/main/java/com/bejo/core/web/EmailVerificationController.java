package com.bejo.core.web;

import com.bejo.core.dto.ResendVerificationRequestDto;
import com.bejo.core.dto.VerifyEmailRequestDto;
import com.bejo.core.service.EmailVerificationService;
import com.bejo.shared.web.ApiResponseDto;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Email verification, before the user has a session (login is refused until it is done). Both endpoints
 * are public, guarded only by {@code X-API-TOKEN}; the code is the credential.
 */
@RestController
@RequestMapping("/api/auth")
public class EmailVerificationController {

    private final EmailVerificationService emailVerificationService;

    public EmailVerificationController(EmailVerificationService emailVerificationService) {
        this.emailVerificationService = emailVerificationService;
    }

    /** Always the same answer for an existing and a non-existing username. */
    @PostMapping("/resend-verification")
    public ResponseEntity<ApiResponseDto<Void>> resend(@Valid @RequestBody ResendVerificationRequestDto request) {
        emailVerificationService.resend(request.username());
        return ResponseEntity.ok(ApiResponseDto.success(
                "Jika akun terdaftar dan belum diverifikasi, kode verifikasi telah dikirim ke email Anda", null));
    }

    @PostMapping("/verify-email")
    public ResponseEntity<ApiResponseDto<Void>> verify(@Valid @RequestBody VerifyEmailRequestDto request) {
        emailVerificationService.verify(request.username(), request.code());
        return ResponseEntity.ok(ApiResponseDto.success(
                "Email berhasil diverifikasi. Silakan masuk", null));
    }
}
