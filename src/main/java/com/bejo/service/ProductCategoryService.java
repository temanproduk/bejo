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

    public ProductCategoryService(ProductCategoryRepository categoryRepository,
                                  ProductRepository productRepository) {
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
    }

    @Transactional
    public CategoryResponseDto create(String username, CreateCategoryRequestDto request) {
        String name = request.name().trim();
        if (categoryRepository.existsByUsernameAndName(username, name)) {
            throw new IllegalArgumentException("Kategori sudah ada: " + name);
        }
        ProductCategory category = new ProductCategory();
        category.setUsername(username);
        category.setName(name);
        categoryRepository.save(category);
        log.info("[CATEGORY] Created id={} name={} for username={}", category.getId(), name, username);
        return toDto(category);
    }

    @Transactional(readOnly = true)
    public List<CategoryResponseDto> list(String username) {
        log.info("[CATEGORY] List for username={}", username);
        return categoryRepository.findByUsernameOrderByNameAsc(username).stream()
                .map(this::toDto)
                .toList();
    }

    @Transactional
    public void delete(String username, Long id) {
        ProductCategory category = categoryRepository.findByIdAndUsername(id, username)
                .orElseThrow(() -> new ResourceNotFoundException("Category not found: " + id));
        int cleared = productRepository.clearCategory(username, id);
        categoryRepository.delete(category);
        log.info("[CATEGORY] Deleted id={} for username={} (uncategorized {} products)", id, username, cleared);
    }

    private CategoryResponseDto toDto(ProductCategory c) {
        return new CategoryResponseDto(c.getId(), c.getName(), c.getCreatedAt());
    }
}
