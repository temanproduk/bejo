package com.bejo.controller;

import com.bejo.dto.request.ChangePasswordRequest;
import com.bejo.dto.request.UpdateBankRequest;
import com.bejo.dto.request.UpdateBusinessRequest;
import com.bejo.dto.request.UpdateProfileRequest;
import com.bejo.dto.response.ApiResponse;
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
    public ResponseEntity<ApiResponse<Void>> updateProfile(
            @Valid @RequestBody UpdateProfileRequest request,
            Authentication authentication) {
        userService.updateProfile(authentication.getName(), request);
        return ResponseEntity.ok(ApiResponse.success("Profile updated successfully", null));
    }

    @PatchMapping("/bank")
    public ResponseEntity<ApiResponse<Void>> updateBank(
            @Valid @RequestBody UpdateBankRequest request,
            Authentication authentication) {
        userService.updateBank(authentication.getName(), request);
        return ResponseEntity.ok(ApiResponse.success("Informasi rekening berhasil diperbarui", null));
    }

    @PostMapping("/bank/reset")
    public ResponseEntity<ApiResponse<Void>> resetBank(Authentication authentication) {
        userService.resetBank(authentication.getName());
        return ResponseEntity.ok(ApiResponse.success("Informasi rekening berhasil direset ke data awal", null));
    }

    @PatchMapping("/business")
    public ResponseEntity<ApiResponse<Void>> updateBusiness(
            @Valid @RequestBody UpdateBusinessRequest request,
            Authentication authentication) {
        userService.updateBusiness(authentication.getName(), request);
        return ResponseEntity.ok(ApiResponse.success("Informasi bisnis berhasil diperbarui", null));
    }

    @PostMapping("/business/reset")
    public ResponseEntity<ApiResponse<Void>> resetBusiness(Authentication authentication) {
        userService.resetBusiness(authentication.getName());
        return ResponseEntity.ok(ApiResponse.success("Informasi bisnis berhasil direset ke data awal", null));
    }

    @PatchMapping("/password")
    public ResponseEntity<ApiResponse<Void>> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            Authentication authentication) {
        userService.changePassword(authentication.getName(), request);
        return ResponseEntity.ok(ApiResponse.success("Password changed successfully", null));
    }
}
