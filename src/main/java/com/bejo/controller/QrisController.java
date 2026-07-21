package com.bejo.controller;

import com.bejo.dto.request.SetUserQrisRequestDto;
import com.bejo.dto.request.UploadQrisRequestDto;
import com.bejo.dto.response.ApiResponseDto;
import com.bejo.dto.response.QrisResponseDto;
import com.bejo.dto.response.UserQrisResponseDto;
import com.bejo.service.QrisService;
import com.bejo.service.UserQrisService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/qris")
public class QrisController {

    private final QrisService qrisService;
    private final UserQrisService userQrisService;

    public QrisController(QrisService qrisService, UserQrisService userQrisService) {
        this.qrisService = qrisService;
        this.userQrisService = userQrisService;
    }

    // The caller's effective QRIS: their own assigned QRIS if the main account gave them one,
    // otherwise the shared business QRIS.
    @GetMapping
    public ResponseEntity<ApiResponseDto<QrisResponseDto>> getQris(Authentication authentication) {
        QrisResponseDto data = qrisService.getQrisData(authentication.getName());
        return ResponseEntity.ok(ApiResponseDto.success(data));
    }

    @PostMapping("/upload")
    public ResponseEntity<ApiResponseDto<Void>> uploadQris(
            @Valid @RequestBody UploadQrisRequestDto request,
            Authentication authentication) {
        qrisService.uploadQris(authentication.getName(), request.qrisImage());
        return ResponseEntity.ok(ApiResponseDto.success("QRIS berhasil diunggah", null));
    }

    @PostMapping("/reset")
    public ResponseEntity<ApiResponseDto<Void>> resetQris(Authentication authentication) {
        qrisService.resetQris(authentication.getName());
        return ResponseEntity.ok(ApiResponseDto.success("QRIS dikembalikan ke bawaan", null));
    }

    // ── Per-user QRIS (main account only) ────────────────────────────────────
    // The main account assigns a user their own QRIS + terminal id so payment callbacks route
    // by terminal. Super admin only (+ team management for the write).

    @PutMapping("/users/{username}")
    public ResponseEntity<ApiResponseDto<UserQrisResponseDto>> setUserQris(
            @PathVariable String username,
            @Valid @RequestBody SetUserQrisRequestDto request,
            Authentication authentication) {
        UserQrisResponseDto data = userQrisService.set(authentication.getName(), username, request);
        return ResponseEntity.ok(ApiResponseDto.success("QRIS pengguna disimpan", data));
    }

    @GetMapping("/users")
    public ResponseEntity<ApiResponseDto<List<UserQrisResponseDto>>> listUserQris(Authentication authentication) {
        return ResponseEntity.ok(ApiResponseDto.list(userQrisService.list(authentication.getName())));
    }

    @GetMapping("/users/{username}")
    public ResponseEntity<ApiResponseDto<UserQrisResponseDto>> getUserQris(
            @PathVariable String username, Authentication authentication) {
        return ResponseEntity.ok(ApiResponseDto.success(userQrisService.get(authentication.getName(), username)));
    }

    @DeleteMapping("/users/{username}")
    public ResponseEntity<ApiResponseDto<Void>> deleteUserQris(
            @PathVariable String username, Authentication authentication) {
        userQrisService.delete(authentication.getName(), username);
        return ResponseEntity.ok(ApiResponseDto.success("QRIS pengguna dihapus", null));
    }
}
