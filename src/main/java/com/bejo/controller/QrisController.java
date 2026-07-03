package com.bejo.controller;

import com.bejo.dto.request.UploadQrisRequestDto;
import com.bejo.dto.response.ApiResponseDto;
import com.bejo.dto.response.QrisResponseDto;
import com.bejo.service.QrisService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/qris")
public class QrisController {

    private final QrisService qrisService;

    public QrisController(QrisService qrisService) {
        this.qrisService = qrisService;
    }

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
        return ResponseEntity.ok(ApiResponseDto.success("QRIS berhasil diupload", null));
    }

    @PostMapping("/reset")
    public ResponseEntity<ApiResponseDto<Void>> resetQris(Authentication authentication) {
        qrisService.resetQris(authentication.getName());
        return ResponseEntity.ok(ApiResponseDto.success("QRIS berhasil direset ke versi generated", null));
    }
}
