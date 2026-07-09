package com.bejo.service;

import com.bejo.constant.StockMutationType;
import com.bejo.dto.request.CreateProductRequestDto;
import com.bejo.dto.request.StockAdjustmentItemDto;
import com.bejo.dto.request.StockAdjustmentRequestDto;
import com.bejo.dto.request.UpdateProductRequestDto;
import com.bejo.dto.response.ProductResponseDto;
import com.bejo.dto.response.StockAdjustmentResultResponseDto;
import com.bejo.entity.Product;
import com.bejo.entity.ProductCategory;
import com.bejo.exception.ResourceNotFoundException;
import com.bejo.repository.ProductCategoryRepository;
import com.bejo.repository.ProductRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * Manages a merchant's products (SKUs) and their basic inventory. All operations are
 * scoped to the authenticated user; a product's category must belong to the same
 * merchant. Inventory management is simply setting {@code stock} on the product.
 */
@Service
public class ProductService {

    private static final Logger log = LoggerFactory.getLogger(ProductService.class);

    // 500 KB — the maximum size of the decoded product photo
    private static final int MAX_PHOTO_BYTES = 512_000;

    private final ProductRepository productRepository;
    private final ProductCategoryRepository categoryRepository;
    private final StockMutationService stockMutationService;

    public ProductService(ProductRepository productRepository,
                          ProductCategoryRepository categoryRepository,
                          StockMutationService stockMutationService) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.stockMutationService = stockMutationService;
    }

    @Transactional
    public ProductResponseDto create(String username, CreateProductRequestDto request) {
        String sku = request.sku().trim();
        if (productRepository.existsByUsernameAndSku(username, sku)) {
            throw new IllegalArgumentException("SKU sudah digunakan: " + sku);
        }
        String categoryName = resolveCategoryName(username, request.categoryId());
        if (request.photo() != null) validatePhoto(request.photo());

        Product p = new Product();
        p.setUsername(username);
        p.setSku(sku);
        p.setName(request.name().trim());
        p.setShortDescription(request.shortDescription());
        p.setCategoryId(request.categoryId());
        p.setPrice(request.price() != null ? request.price() : 0L);
        p.setCostPrice(request.costPrice());
        p.setStock(request.stock() != null ? request.stock() : 0);
        p.setPhoto(request.photo());
        productRepository.save(p);
        log.info("[PRODUCT] Created id={} sku={} for username={}", p.getId(), sku, username);
        return toDto(p, categoryName, true);
    }

    @Transactional(readOnly = true)
    public Page<ProductResponseDto> list(String username, Long categoryId, Pageable pageable) {
        log.info("[PRODUCT] List for username={} categoryId={} page={} size={}",
                username, categoryId, pageable.getPageNumber(), pageable.getPageSize());
        Page<Product> products = categoryId != null
                ? productRepository.findByUsernameAndCategoryIdOrderByNameAsc(username, categoryId, pageable)
                : productRepository.findByUsernameOrderByNameAsc(username, pageable);

        // Resolve category names in one shot; omit photos to keep the list small
        Map<Long, String> names = categoryNames(username);
        return products.map(p -> toDto(p, names.get(p.getCategoryId()), false));
    }

    @Transactional(readOnly = true)
    public ProductResponseDto get(String username, Long id) {
        log.info("[PRODUCT] Get id={} for username={}", id, username);
        Product p = productRepository.findByIdAndUsername(id, username)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id));
        return toDto(p, categoryName(username, p.getCategoryId()), true);
    }

    @Transactional
    public ProductResponseDto update(String username, Long id, UpdateProductRequestDto request) {
        if (request.isEmpty()) {
            throw new IllegalArgumentException("Tidak ada field yang diubah");
        }
        Product p = productRepository.findByIdAndUsername(id, username)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id));

        if (request.sku() != null) {
            String sku = request.sku().trim();
            if (sku.isBlank()) throw new IllegalArgumentException("sku tidak boleh kosong");
            if (!sku.equals(p.getSku()) && productRepository.existsByUsernameAndSku(username, sku)) {
                throw new IllegalArgumentException("SKU sudah digunakan: " + sku);
            }
            p.setSku(sku);
        }
        if (request.name() != null) {
            if (request.name().isBlank()) throw new IllegalArgumentException("name tidak boleh kosong");
            p.setName(request.name().trim());
        }
        if (request.shortDescription() != null) p.setShortDescription(request.shortDescription());
        if (request.categoryId() != null) {
            resolveCategoryName(username, request.categoryId()); // validates ownership
            p.setCategoryId(request.categoryId());
        }
        if (request.price() != null) p.setPrice(request.price());
        if (request.costPrice() != null) p.setCostPrice(request.costPrice());
        int previousStock = p.getStock();
        boolean stockChanged = request.stock() != null && request.stock() != previousStock;
        if (request.stock() != null) p.setStock(request.stock());
        if (request.photo() != null) {
            validatePhoto(request.photo());
            p.setPhoto(request.photo());
        }
        productRepository.save(p);
        // Record a stock mutation only when the edit actually changed the stock
        if (stockChanged) {
            stockMutationService.recordStockChange(username, StockMutationType.UPDATE, "Product update",
                    List.of(new StockMutationService.StockChangeLine(p, previousStock, p.getStock())));
        }
        log.info("[PRODUCT] Updated id={} for username={}", id, username);
        return toDto(p, categoryName(username, p.getCategoryId()), true);
    }

    /**
     * Bulk stock adjustment (stock opname): sets each listed product's stock to its new
     * counted quantity. Atomic and two-pass — pass 1 resolves every line to one of the
     * merchant's own products (unknown → 400, nothing changes); pass 2 applies the new
     * counts and returns a variance report (previous → new → difference).
     */
    @Transactional
    public StockAdjustmentResultResponseDto adjustStock(String username, StockAdjustmentRequestDto request) {
        List<StockAdjustmentItemDto> items = request.items();
        log.info("[PRODUCT] Stock adjustment for username={} lines={}", username, items.size());

        // Pass 1 — resolve every line to an owned product before changing anything
        List<Product> products = new ArrayList<>();
        for (StockAdjustmentItemDto item : items) {
            products.add(productRepository.findByIdAndUsername(item.productId(), username)
                    .orElseThrow(() -> new IllegalArgumentException("Produk tidak ditemukan: " + item.productId())));
        }

        // Pass 2 — apply the new counts, record the mutation, build the variance report
        List<StockAdjustmentResultResponseDto.ItemResult> results = new ArrayList<>();
        List<StockMutationService.StockChangeLine> changes = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            Product product = products.get(i);
            int previous = product.getStock();
            int counted = items.get(i).stock();
            product.setStock(counted);
            productRepository.save(product);
            changes.add(new StockMutationService.StockChangeLine(product, previous, counted));
            results.add(new StockAdjustmentResultResponseDto.ItemResult(
                    product.getId(), product.getSku(), product.getName(),
                    previous, counted, counted - previous));
        }
        String note = request.note() != null && !request.note().isBlank() ? request.note().trim() : "Stock opname";
        stockMutationService.recordStockChange(username, StockMutationType.ADJUSTMENT, note, changes);

        log.info("[PRODUCT] Stock adjustment done for username={} products={}", username, results.size());
        return new StockAdjustmentResultResponseDto(results.size(), results);
    }

    @Transactional
    public void delete(String username, Long id) {
        Product p = productRepository.findByIdAndUsername(id, username)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id));
        productRepository.delete(p);
        log.info("[PRODUCT] Deleted id={} for username={}", id, username);
    }

    // Validates that the category exists and belongs to this merchant; returns its name.
    private String resolveCategoryName(String username, Long categoryId) {
        if (categoryId == null) return null;
        ProductCategory category = categoryRepository.findByIdAndUsername(categoryId, username)
                .orElseThrow(() -> new IllegalArgumentException("Kategori tidak ditemukan: " + categoryId));
        return category.getName();
    }

    private String categoryName(String username, Long categoryId) {
        if (categoryId == null) return null;
        return categoryRepository.findByIdAndUsername(categoryId, username)
                .map(ProductCategory::getName)
                .orElse(null);
    }

    private Map<Long, String> categoryNames(String username) {
        return categoryRepository.findByUsernameOrderByNameAsc(username).stream()
                .collect(Collectors.toMap(ProductCategory::getId, ProductCategory::getName));
    }

    private void validatePhoto(String base64Image) {
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(base64Image);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("photo bukan format base64 yang valid");
        }
        if (decoded.length > MAX_PHOTO_BYTES) {
            throw new IllegalArgumentException("photo terlalu besar (maksimal 500KB)");
        }
    }

    private ProductResponseDto toDto(Product p, String categoryName, boolean includePhoto) {
        return new ProductResponseDto(
                p.getId(), p.getSku(), p.getName(), p.getShortDescription(),
                p.getCategoryId(), categoryName,
                p.getPrice(), p.getCostPrice(), p.getStock(),
                includePhoto ? p.getPhoto() : null,
                p.getCreatedAt(), p.getUpdatedAt());
    }
}
