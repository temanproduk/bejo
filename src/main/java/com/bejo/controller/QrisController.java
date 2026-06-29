package com.bejo.controller;

import com.bejo.dto.request.UploadQrisRequest;
import com.bejo.dto.response.ApiResponse;
import com.bejo.dto.response.QrisData;
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
    public ResponseEntity<ApiResponse<QrisData>> getQris(Authentication authentication) {
        QrisData data = qrisService.getQrisData(authentication.getName());
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @PostMapping("/upload")
    public ResponseEntity<ApiResponse<Void>> uploadQris(
            @Valid @RequestBody UploadQrisRequest request,
            Authentication authentication) {
        qrisService.uploadQris(authentication.getName(), request.image());
        return ResponseEntity.ok(ApiResponse.success("QRIS berhasil diupload", null));
    }

    @PostMapping("/reset")
    public ResponseEntity<ApiResponse<Void>> resetQris(Authentication authentication) {
        qrisService.resetQris(authentication.getName());
        return ResponseEntity.ok(ApiResponse.success("QRIS berhasil direset ke versi generated", null));
    }
}
