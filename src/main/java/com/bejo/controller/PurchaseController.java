package com.bejo.controller;

import com.bejo.dto.request.CreatePurchaseRequestDto;
import com.bejo.dto.response.ApiResponseDto;
import com.bejo.dto.response.PurchaseResponseDto;
import com.bejo.service.PurchaseService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/purchases")
public class PurchaseController {

    private final PurchaseService purchaseService;

    public PurchaseController(PurchaseService purchaseService) {
        this.purchaseService = purchaseService;
    }

    // Record a purchase order (restock): raises the stock of every listed product
    // atomically and saves the purchase with its line items.
    @PostMapping
    public ResponseEntity<ApiResponseDto<PurchaseResponseDto>> create(
            @Valid @RequestBody CreatePurchaseRequestDto request,
            Authentication authentication) {
        PurchaseResponseDto data = purchaseService.create(authentication.getName(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseDto.success("Pembelian berhasil dicatat", data));
    }

    @GetMapping
    public ResponseEntity<ApiResponseDto<List<PurchaseResponseDto>>> list(Authentication authentication) {
        List<PurchaseResponseDto> data = purchaseService.list(authentication.getName());
        return ResponseEntity.ok(ApiResponseDto.list(data));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponseDto<PurchaseResponseDto>> get(
            @PathVariable Long id,
            Authentication authentication) {
        PurchaseResponseDto data = purchaseService.get(authentication.getName(), id);
        return ResponseEntity.ok(ApiResponseDto.success(data));
    }
}
