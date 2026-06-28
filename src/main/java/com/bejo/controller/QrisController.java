package com.bejo.controller;

import com.bejo.dto.response.ApiResponse;
import com.bejo.dto.response.QrisData;
import com.bejo.service.QrisService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.MediaType;
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
    public ResponseEntity<ApiResponse<QrisData>> getQris(
            Authentication authentication,
            HttpServletRequest request) {
        QrisData data = qrisService.getQrisData(authentication.getName(), request);
        return ResponseEntity.ok(ApiResponse.success(data));
    }

    @GetMapping(value = "/image/{qrisCode}.png", produces = MediaType.IMAGE_PNG_VALUE)
    public ResponseEntity<byte[]> getQrisImage(@PathVariable String qrisCode) throws Exception {
        byte[] image = qrisService.generateImage(qrisCode);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .body(image);
    }
}
