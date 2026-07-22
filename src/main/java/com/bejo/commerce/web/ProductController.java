package com.bejo.commerce.web;

import com.bejo.commerce.dto.CreateProductRequestDto;
import com.bejo.commerce.dto.UpdateProductRequestDto;
import com.bejo.shared.web.ApiResponseDto;
import com.bejo.commerce.dto.ProductResponseDto;
import com.bejo.commerce.service.ProductService;
import com.bejo.shared.util.Pagination;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    @PostMapping
    public ResponseEntity<ApiResponseDto<ProductResponseDto>> create(
            @Valid @RequestBody CreateProductRequestDto request,
            Authentication authentication) {
        ProductResponseDto data = productService.create(authentication.getName(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseDto.success("Produk berhasil dibuat", data));
    }

    // Pagination is optional: omit page/size to get page 1 of 50. The response
    // carries page/totalPages/total alongside the current page's items.
    @GetMapping
    public ResponseEntity<ApiResponseDto<List<ProductResponseDto>>> list(
            @RequestParam(required = false) Long categoryId,
            @RequestParam(required = false) Integer page,
            @RequestParam(required = false) Integer size,
            Authentication authentication) {
        Page<ProductResponseDto> data = productService.list(
                authentication.getName(), categoryId, Pagination.of(page, size));
        return ResponseEntity.ok(ApiResponseDto.page(data));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponseDto<ProductResponseDto>> get(
            @PathVariable Long id,
            Authentication authentication) {
        ProductResponseDto data = productService.get(authentication.getName(), id);
        return ResponseEntity.ok(ApiResponseDto.success(data));
    }

    @PatchMapping("/{id}")
    public ResponseEntity<ApiResponseDto<ProductResponseDto>> update(
            @PathVariable Long id,
            @Valid @RequestBody UpdateProductRequestDto request,
            Authentication authentication) {
        ProductResponseDto data = productService.update(authentication.getName(), id, request);
        return ResponseEntity.ok(ApiResponseDto.success("Produk berhasil diubah", data));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponseDto<Void>> delete(
            @PathVariable Long id,
            Authentication authentication) {
        productService.delete(authentication.getName(), id);
        return ResponseEntity.ok(ApiResponseDto.success("Produk berhasil dihapus", null));
    }
}
