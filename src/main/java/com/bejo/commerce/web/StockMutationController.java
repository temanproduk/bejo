package com.bejo.commerce.web;

import com.bejo.commerce.dto.CreatePurchaseRequestDto;
import com.bejo.shared.web.ApiResponseDto;
import com.bejo.commerce.dto.StockMutationResponseDto;
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
