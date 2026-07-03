package com.bejo.controller;

import com.bejo.dto.request.ChangePasswordRequestDto;
import com.bejo.dto.request.UpdateBankRequestDto;
import com.bejo.dto.request.UpdateBusinessRequestDto;
import com.bejo.dto.request.UpdateProfileRequestDto;
import com.bejo.dto.response.ApiResponseDto;
import com.bejo.dto.response.BankResponseDto;
import com.bejo.dto.response.BusinessResponseDto;
import com.bejo.service.UserService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users/me")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PatchMapping
    public ResponseEntity<ApiResponseDto<Void>> updateProfile(
            @Valid @RequestBody UpdateProfileRequestDto request,
            Authentication authentication) {
        userService.updateProfile(authentication.getName(), request);
        return ResponseEntity.ok(ApiResponseDto.success("Profile updated successfully", null));
    }

    @PatchMapping("/bank")
    public ResponseEntity<ApiResponseDto<Void>> updateBank(
            @Valid @RequestBody UpdateBankRequestDto request,
            Authentication authentication) {
        userService.updateBank(authentication.getName(), request);
        return ResponseEntity.ok(ApiResponseDto.success("Informasi rekening berhasil diperbarui", null));
    }

    @PostMapping("/bank/reset")
    public ResponseEntity<ApiResponseDto<BankResponseDto>> resetBank(Authentication authentication) {
        BankResponseDto data = userService.resetBank(authentication.getName());
        return ResponseEntity.ok(ApiResponseDto.success("Informasi rekening berhasil direset ke data awal", data));
    }

    @PatchMapping("/business")
    public ResponseEntity<ApiResponseDto<Void>> updateBusiness(
            @Valid @RequestBody UpdateBusinessRequestDto request,
            Authentication authentication) {
        userService.updateBusiness(authentication.getName(), request);
        return ResponseEntity.ok(ApiResponseDto.success("Informasi bisnis berhasil diperbarui", null));
    }

    @PostMapping("/business/reset")
    public ResponseEntity<ApiResponseDto<BusinessResponseDto>> resetBusiness(Authentication authentication) {
        BusinessResponseDto data = userService.resetBusiness(authentication.getName());
        return ResponseEntity.ok(ApiResponseDto.success("Informasi bisnis berhasil direset ke data awal", data));
    }

    @PatchMapping("/password")
    public ResponseEntity<ApiResponseDto<Void>> changePassword(
            @Valid @RequestBody ChangePasswordRequestDto request,
            Authentication authentication) {
        userService.changePassword(authentication.getName(), request);
        return ResponseEntity.ok(ApiResponseDto.success("Password changed successfully", null));
    }
}
