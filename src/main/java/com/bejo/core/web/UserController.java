package com.bejo.core.web;

import com.bejo.core.dto.ChangePasswordRequestDto;
import com.bejo.core.domain.User;
import com.bejo.core.dto.RegisterRequestDto;
import com.bejo.core.dto.RegisterResponseDto;
import com.bejo.core.dto.UpdateBankRequestDto;
import com.bejo.core.dto.UpdateBusinessRequestDto;
import com.bejo.core.dto.UpdateProfileRequestDto;
import com.bejo.core.dto.UpgradeAccountRequestDto;
import com.bejo.shared.web.ApiResponseDto;
import com.bejo.core.dto.MeResponseDto;
import com.bejo.core.service.EmailVerificationService;
import com.bejo.core.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    private final EmailVerificationService emailVerificationService;

    public UserController(UserService userService, EmailVerificationService emailVerificationService) {
        this.userService = userService;
        this.emailVerificationService = emailVerificationService;
    }

    // Public self-service registration — creates a new business and its first user
    // (the SUPER_ADMIN). Additional users are added by a super admin via /api/users/members.
    // The account cannot log in until its email is verified: the emailed code is confirmed through
    // POST /api/auth/verify-email.
    @PostMapping("/register")
    public ResponseEntity<ApiResponseDto<RegisterResponseDto>> register(@Valid @RequestBody RegisterRequestDto request) {
        // Without email a new account could never be verified, so refuse before creating anything
        emailVerificationService.requireEnabled();
        User user = userService.register(request);
        // After the registration committed: a mail failure must not undo it (the user can resend)
        boolean sent = emailVerificationService.sendCode(user.getUsername());
        String message = sent
                ? "Registrasi berhasil. Masukkan kode verifikasi yang dikirim ke email Anda"
                : "Registrasi berhasil, tetapi kode verifikasi belum terkirim. Minta kode baru untuk melanjutkan";
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponseDto.success(message,
                new RegisterResponseDto(true, sent, EmailVerificationService.maskEmail(user.getEmail()))));
    }

    // The caller's own account bundle (profile + role + shared business/bank/picture/app
    // settings) — same shape as the login response, for refreshing state without re-login.
    @GetMapping("/me")
    public ResponseEntity<ApiResponseDto<MeResponseDto>> getMe(Authentication authentication) {
        MeResponseDto data = userService.getMe(authentication.getName());
        return ResponseEntity.ok(ApiResponseDto.success(data));
    }

    @PatchMapping("/me")
    public ResponseEntity<ApiResponseDto<Void>> updateProfile(
            @Valid @RequestBody UpdateProfileRequestDto request,
            Authentication authentication) {
        boolean emailChanged = userService.updateProfile(authentication.getName(), request);
        // A new address must be confirmed before the next login; send its code once the update committed
        boolean sent = emailChanged && emailVerificationService.sendCode(authentication.getName(), true);
        return ResponseEntity.ok(ApiResponseDto.success(sent
                ? "Profil berhasil diperbarui. Kode verifikasi dikirim ke email baru Anda"
                : "Profil berhasil diperbarui", null));
    }

    @PatchMapping("/me/bank")
    public ResponseEntity<ApiResponseDto<Void>> updateBank(
            @Valid @RequestBody UpdateBankRequestDto request,
            Authentication authentication) {
        userService.updateBank(authentication.getName(), request);
        return ResponseEntity.ok(ApiResponseDto.success("Rekening berhasil diperbarui", null));
    }


    @PatchMapping("/me/business")
    public ResponseEntity<ApiResponseDto<Void>> updateBusiness(
            @Valid @RequestBody UpdateBusinessRequestDto request,
            Authentication authentication) {
        userService.updateBusiness(authentication.getName(), request);
        return ResponseEntity.ok(ApiResponseDto.success("Info bisnis berhasil diperbarui", null));
    }


    // Upgrade a self-service account to be managed by Bejo — requires merchant PAN + NMID.
    @PostMapping("/me/upgrade")
    public ResponseEntity<ApiResponseDto<MeResponseDto>> upgradeToManaged(
            @Valid @RequestBody UpgradeAccountRequestDto request,
            Authentication authentication) {
        MeResponseDto data = userService.upgradeToManaged(authentication.getName(), request);
        return ResponseEntity.ok(ApiResponseDto.success("Akun berhasil ditingkatkan ke Managed", data));
    }

    @PatchMapping("/me/password")
    public ResponseEntity<ApiResponseDto<Void>> changePassword(
            @Valid @RequestBody ChangePasswordRequestDto request,
            Authentication authentication) {
        userService.changePassword(authentication.getName(), request);
        return ResponseEntity.ok(ApiResponseDto.success("Kata sandi berhasil diubah", null));
    }
}
