package com.bejo.service;

import com.bejo.dto.request.CreateCategoryRequestDto;
import com.bejo.dto.response.CategoryResponseDto;
import com.bejo.entity.ProductCategory;
import com.bejo.exception.ResourceNotFoundException;
import com.bejo.repository.ProductCategoryRepository;
import com.bejo.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Manages a merchant's product categories. Names are unique per merchant. Deleting a
 * category first un-categorizes its products (sets their {@code category_id} to null),
 * so products are never removed along with the category.
 */
@Service
public class ProductCategoryService {

    private static final Logger log = LoggerFactory.getLogger(ProductCategoryService.class);

    private final ProductCategoryRepository categoryRepository;
    private final ProductRepository productRepository;
    private final BusinessContext businessContext;

    public ProductCategoryService(ProductCategoryRepository categoryRepository,
                                  ProductRepository productRepository,
                                  BusinessContext businessContext) {
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
        this.businessContext = businessContext;
    }

    @Transactional
    public CategoryResponseDto create(String username, CreateCategoryRequestDto request) {
        Long businessId = businessContext.requireBusinessId(username);
        String name = request.name().trim();
        if (categoryRepository.existsByBusinessIdAndName(businessId, name)) {
            throw new IllegalArgumentException("Kategori sudah ada: " + name);
        }
        ProductCategory category = new ProductCategory();
        category.setBusinessId(businessId);
        category.setName(name);
        categoryRepository.save(category);
        log.info("[CATEGORY] Created id={} name={} for businessId={}", category.getId(), name, businessId);
        return toDto(category);
    }

    @Transactional(readOnly = true)
    public List<CategoryResponseDto> list(String username) {
        Long businessId = businessContext.requireBusinessId(username);
        log.info("[CATEGORY] List for businessId={}", businessId);
        return categoryRepository.findByBusinessIdOrderByNameAsc(businessId).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public void delete(String username, Long id) {
        Long businessId = businessContext.requireBusinessId(username);
        ProductCategory category = categoryRepository.findByIdAndBusinessId(id, businessId)
                .orElseThrow(() -> new ResourceNotFoundException("Kategori tidak ditemukan: " + id));
        int cleared = productRepository.clearCategory(businessId, id);
        categoryRepository.delete(category);
        log.info("[CATEGORY] Deleted id={} for businessId={} (uncategorized {} products)", id, businessId, cleared);
    }

    private CategoryResponseDto toDto(ProductCategory c) {
        return new CategoryResponseDto(c.getId(), c.getName(), c.getCreatedAt());
    }
}
