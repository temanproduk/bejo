package com.bejo.commerce.web;

import com.bejo.commerce.dto.CreatePurchaseRequestDto;
import com.bejo.commerce.dto.StockAdjustmentRequestDto;
import com.bejo.commerce.dto.StockAdjustmentResultResponseDto;
import com.bejo.shared.web.ApiResponseDto;
import com.bejo.commerce.dto.StockMutationResponseDto;
import com.bejo.commerce.service.ProductService;
import com.bejo.commerce.service.StockMutationService;
import com.bejo.shared.util.Pagination;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * Stock operations — every recorded change to product stock (PURCHASE restock,
 * ADJUSTMENT opname, UPDATE product edit). This controller owns the write endpoints that
 * create stock mutations — PURCHASE (restock) and ADJUSTMENT (stock opname) — and reads the
 * full history; UPDATE mutations are still created implicitly by a product edit.
 */
@RestController
@RequestMapping("/api/stock-mutations")
public class StockMutationController {

    private final StockMutationService stockMutationService;
    private final ProductService productService;

    public StockMutationController(StockMutationService stockMutationService, ProductService productService) {
        this.stockMutationService = stockMutationService;
        this.productService = productService;
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

    // Stock opname: set several products' stock to their counted values atomically and
    // record one ADJUSTMENT stock mutation; returns a per-product variance report.
    @PostMapping("/adjustments")
    public ResponseEntity<ApiResponseDto<StockAdjustmentResultResponseDto>> adjustStock(
            @Valid @RequestBody StockAdjustmentRequestDto request,
            Authentication authentication) {
        StockAdjustmentResultResponseDto data = productService.adjustStock(authentication.getName(), request);
        return ResponseEntity.ok(ApiResponseDto.success("Penyesuaian stok berhasil", data));
    }

    // Pagination is optional: omit page/size to get page 1 of 50. The response
    // carries page/totalPages/total alongside the current page's items.
    @GetMapping
    public ResponseEntity<ApiResponseDto<List<StockMutationResponseDto>>> list(
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            Authentication authentication) {
        Page<StockMutationResponseDto> data = stockMutationService.list(
                authentication.getName(), Pagination.of(page, size));
        return ResponseEntity.ok(ApiResponseDto.page(data));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponseDto<StockMutationResponseDto>> get(
            @PathVariable Long id,
            Authentication authentication) {
        StockMutationResponseDto data = stockMutationService.get(authentication.getName(), id);
        return ResponseEntity.ok(ApiResponseDto.success(data));
    }
}
