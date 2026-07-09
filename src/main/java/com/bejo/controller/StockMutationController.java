package com.bejo.controller;

import com.bejo.dto.request.CreatePurchaseRequestDto;
import com.bejo.dto.response.ApiResponseDto;
import com.bejo.dto.response.StockMutationResponseDto;
import com.bejo.service.StockMutationService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Stock mutation history — every recorded change to product stock (PURCHASE restock,
 * ADJUSTMENT opname, UPDATE product edit). ADJUSTMENT/UPDATE mutations are created
 * implicitly by the product APIs; this controller creates PURCHASE (restock) mutations
 * and reads the full history.
 */
@RestController
@RequestMapping("/api/stock-mutations")
public class StockMutationController {

    private final StockMutationService stockMutationService;

    public StockMutationController(StockMutationService stockMutationService) {
        this.stockMutationService = stockMutationService;
    }

    // Record a purchase (restock): raises the stock of every listed product
    // atomically and saves a PURCHASE stock mutation with its line items.
    @PostMapping
    public ResponseEntity<ApiResponseDto<StockMutationResponseDto>> createPurchase(
            @Valid @RequestBody CreatePurchaseRequestDto request,
            Authentication authentication) {
        StockMutationResponseDto data = stockMutationService.create(authentication.getName(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseDto.success("Pembelian berhasil dicatat", data));
    }

    @GetMapping
    public ResponseEntity<ApiResponseDto<List<StockMutationResponseDto>>> list(Authentication authentication) {
        List<StockMutationResponseDto> data = stockMutationService.list(authentication.getName());
        return ResponseEntity.ok(ApiResponseDto.list(data));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponseDto<StockMutationResponseDto>> get(
            @PathVariable Long id,
            Authentication authentication) {
        StockMutationResponseDto data = stockMutationService.get(authentication.getName(), id);
        return ResponseEntity.ok(ApiResponseDto.success(data));
    }
}
