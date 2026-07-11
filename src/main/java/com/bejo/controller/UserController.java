package com.bejo.controller;

import com.bejo.dto.request.ChangePasswordRequestDto;
import com.bejo.dto.request.RegisterRequestDto;
import com.bejo.dto.request.UpdateBankRequestDto;
import com.bejo.dto.request.UpdateBusinessRequestDto;
import com.bejo.dto.request.UpdateProfileRequestDto;
import com.bejo.dto.response.ApiResponseDto;
import com.bejo.dto.response.BankResponseDto;
import com.bejo.dto.response.BusinessResponseDto;
import com.bejo.dto.response.MeResponseDto;
import com.bejo.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    // Public self-service registration — creates a new business and its first user
    // (the SUPER_ADMIN). Additional users are added by a super admin via /api/users/members.
    @PostMapping("/register")
    public ResponseEntity<ApiResponseDto<Void>> register(@Valid @RequestBody RegisterRequestDto request) {
        userService.register(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseDto.success("Registrasi berhasil", null));
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
        userService.updateProfile(authentication.getName(), request);
        return ResponseEntity.ok(ApiResponseDto.success("Profile updated successfully", null));
    }

    @PatchMapping("/me/bank")
    public ResponseEntity<ApiResponseDto<Void>> updateBank(
            @Valid @RequestBody UpdateBankRequestDto request,
            Authentication authentication) {
        userService.updateBank(authentication.getName(), request);
        return ResponseEntity.ok(ApiResponseDto.success("Informasi rekening berhasil diperbarui", null));
    }

    @PostMapping("/me/bank/reset")
    public ResponseEntity<ApiResponseDto<BankResponseDto>> resetBank(Authentication authentication) {
        BankResponseDto data = userService.resetBank(authentication.getName());
        return ResponseEntity.ok(ApiResponseDto.success("Informasi rekening berhasil direset ke data awal", data));
    }

    @PatchMapping("/me/business")
    public ResponseEntity<ApiResponseDto<Void>> updateBusiness(
            @Valid @RequestBody UpdateBusinessRequestDto request,
            Authentication authentication) {
        userService.updateBusiness(authentication.getName(), request);
        return ResponseEntity.ok(ApiResponseDto.success("Informasi bisnis berhasil diperbarui", null));
    }

    @PostMapping("/me/business/reset")
    public ResponseEntity<ApiResponseDto<BusinessResponseDto>> resetBusiness(Authentication authentication) {
        BusinessResponseDto data = userService.resetBusiness(authentication.getName());
        return ResponseEntity.ok(ApiResponseDto.success("Informasi bisnis berhasil direset ke data awal", data));
    }

    @PatchMapping("/me/password")
    public ResponseEntity<ApiResponseDto<Void>> changePassword(
            @Valid @RequestBody ChangePasswordRequestDto request,
            Authentication authentication) {
        userService.changePassword(authentication.getName(), request);
        return ResponseEntity.ok(ApiResponseDto.success("Password changed successfully", null));
    }
}
