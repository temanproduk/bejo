package com.bejo.core.web;

import com.bejo.core.dto.SetUserQrisRequestDto;
import com.bejo.core.dto.UploadQrisRequestDto;
import com.bejo.shared.web.ApiResponseDto;
import com.bejo.core.dto.QrisResponseDto;
import com.bejo.core.dto.UserQrisResponseDto;
import com.bejo.core.service.QrisService;
import com.bejo.core.service.UserQrisService;
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
        qrisService.uploadQris(authentication.getName(), request.qrisCode());
        return ResponseEntity.ok(ApiResponseDto.success("QRIS berhasil diunggah", null));
    }


    // ── Per-user QRIS (main account only) ────────────────────────────────────
    // The main account assigns a user their own QRIS + terminal id so payment callbacks route
    // by terminal. Super admin only (+ team management for the write).

    // Keyed by user id (like /api/users/members/{id}) for a consistent identifier across the API.
    @PutMapping("/users/{id}")
    public ResponseEntity<ApiResponseDto<UserQrisResponseDto>> setUserQris(
            @PathVariable Long id,
            @Valid @RequestBody SetUserQrisRequestDto request,
            Authentication authentication) {
        UserQrisResponseDto data = userQrisService.set(authentication.getName(), id, request);
        return ResponseEntity.ok(ApiResponseDto.success("QRIS pengguna disimpan", data));
    }

    @GetMapping("/users")
    public ResponseEntity<ApiResponseDto<List<UserQrisResponseDto>>> listUserQris(Authentication authentication) {
        return ResponseEntity.ok(ApiResponseDto.list(userQrisService.list(authentication.getName())));
    }

    @GetMapping("/users/{id}")
    public ResponseEntity<ApiResponseDto<UserQrisResponseDto>> getUserQris(
            @PathVariable Long id, Authentication authentication) {
        return ResponseEntity.ok(ApiResponseDto.success(userQrisService.get(authentication.getName(), id)));
    }

    @DeleteMapping("/users/{id}")
    public ResponseEntity<ApiResponseDto<Void>> deleteUserQris(
            @PathVariable Long id, Authentication authentication) {
        userQrisService.delete(authentication.getName(), id);
        return ResponseEntity.ok(ApiResponseDto.success("QRIS pengguna dihapus", null));
    }
}
