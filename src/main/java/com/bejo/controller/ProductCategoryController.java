package com.bejo.controller;

import com.bejo.dto.request.CreateCategoryRequestDto;
import com.bejo.dto.response.ApiResponseDto;
import com.bejo.dto.response.CategoryResponseDto;
import com.bejo.service.ProductCategoryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/categories")
public class ProductCategoryController {

    private final ProductCategoryService categoryService;

    public ProductCategoryController(ProductCategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @PostMapping
    public ResponseEntity<ApiResponseDto<CategoryResponseDto>> create(
            @Valid @RequestBody CreateCategoryRequestDto request,
            Authentication authentication) {
        CategoryResponseDto data = categoryService.create(authentication.getName(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponseDto.success("Kategori berhasil dibuat", data));
    }

    @GetMapping
    public ResponseEntity<ApiResponseDto<List<CategoryResponseDto>>> list(Authentication authentication) {
        List<CategoryResponseDto> data = categoryService.list(authentication.getName());
        return ResponseEntity.ok(ApiResponseDto.list(data));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponseDto<Void>> delete(
            @PathVariable Long id,
            Authentication authentication) {
        categoryService.delete(authentication.getName(), id);
        return ResponseEntity.ok(ApiResponseDto.success("Kategori berhasil dihapus", null));
    }
}
