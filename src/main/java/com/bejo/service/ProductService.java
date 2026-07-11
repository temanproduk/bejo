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
    private final BusinessContext businessContext;

    public ProductService(ProductRepository productRepository,
                          ProductCategoryRepository categoryRepository,
                          StockMutationService stockMutationService,
                          BusinessContext businessContext) {
        this.productRepository = productRepository;
        this.categoryRepository = categoryRepository;
        this.stockMutationService = stockMutationService;
        this.businessContext = businessContext;
    }

    @Transactional
    public ProductResponseDto create(String username, CreateProductRequestDto request) {
        Long businessId = businessContext.requireBusinessId(username);
        String sku = request.sku().trim();
        if (productRepository.existsByBusinessIdAndSku(businessId, sku)) {
            throw new IllegalArgumentException("SKU sudah digunakan: " + sku);
        }
        String categoryName = resolveCategoryName(businessId, request.categoryId());
        if (request.photo() != null) validatePhoto(request.photo());

        Product p = new Product();
        p.setBusinessId(businessId);
        p.setSku(sku);
        p.setName(request.name().trim());
        p.setShortDescription(request.shortDescription());
        p.setCategoryId(request.categoryId());
        p.setPrice(request.price() != null ? request.price() : 0L);
        p.setCostPrice(request.costPrice());
        p.setStock(request.stock() != null ? request.stock() : 0);
        p.setPhoto(request.photo());
        productRepository.save(p);
        log.info("[PRODUCT] Created id={} sku={} for businessId={}", p.getId(), sku, businessId);
        return toDto(p, categoryName, true);
    }

    @Transactional(readOnly = true)
    public Page<ProductResponseDto> list(String username, Long categoryId, Pageable pageable) {
        Long businessId = businessContext.requireBusinessId(username);
        log.info("[PRODUCT] List for businessId={} categoryId={} page={} size={}",
                businessId, categoryId, pageable.getPageNumber(), pageable.getPageSize());
        Page<Product> products = categoryId != null
                ? productRepository.findByBusinessIdAndCategoryIdOrderByNameAsc(businessId, categoryId, pageable)
                : productRepository.findByBusinessIdOrderByNameAsc(businessId, pageable);

        // Resolve category names in one shot; omit photos to keep the list small
        Map<Long, String> names = categoryNames(businessId);
        return products.map(p -> toDto(p, names.get(p.getCategoryId()), false));
    }

    @Transactional(readOnly = true)
    public ProductResponseDto get(String username, Long id) {
        Long businessId = businessContext.requireBusinessId(username);
        log.info("[PRODUCT] Get id={} for businessId={}", id, businessId);
        Product p = productRepository.findByIdAndBusinessId(id, businessId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id));
        return toDto(p, categoryName(businessId, p.getCategoryId()), true);
    }

    @Transactional
    public ProductResponseDto update(String username, Long id, UpdateProductRequestDto request) {
        if (request.isEmpty()) {
            throw new IllegalArgumentException("Tidak ada field yang diubah");
        }
        Long businessId = businessContext.requireBusinessId(username);
        Product p = productRepository.findByIdAndBusinessId(id, businessId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id));

        if (request.sku() != null) {
            String sku = request.sku().trim();
            if (sku.isBlank()) throw new IllegalArgumentException("sku tidak boleh kosong");
            if (!sku.equals(p.getSku()) && productRepository.existsByBusinessIdAndSku(businessId, sku)) {
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
            resolveCategoryName(businessId, request.categoryId()); // validates ownership
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
            stockMutationService.recordStockChange(businessId, StockMutationType.UPDATE, "Product update",
                    List.of(new StockMutationService.StockChangeLine(p, previousStock, p.getStock())));
        }
        log.info("[PRODUCT] Updated id={} for businessId={}", id, businessId);
        return toDto(p, categoryName(businessId, p.getCategoryId()), true);
    }

    /**
     * Bulk stock adjustment (stock opname): sets each listed product's stock to its new
     * counted quantity. Atomic and two-pass — pass 1 resolves every line to one of the
     * merchant's own products (unknown → 400, nothing changes); pass 2 applies the new
     * counts and returns a variance report (previous → new → difference).
     */
    @Transactional
    public StockAdjustmentResultResponseDto adjustStock(String username, StockAdjustmentRequestDto request) {
        Long businessId = businessContext.requireBusinessId(username);
        List<StockAdjustmentItemDto> items = request.items();
        log.info("[PRODUCT] Stock adjustment for businessId={} lines={}", businessId, items.size());

        // Pass 1 — resolve every line to an owned product before changing anything
        List<Product> products = new ArrayList<>();
        for (StockAdjustmentItemDto item : items) {
            products.add(productRepository.findByIdAndBusinessId(item.productId(), businessId)
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
        stockMutationService.recordStockChange(businessId, StockMutationType.ADJUSTMENT, note, changes);

        log.info("[PRODUCT] Stock adjustment done for businessId={} products={}", businessId, results.size());
        return new StockAdjustmentResultResponseDto(results.size(), results);
    }

    @Transactional
    public void delete(String username, Long id) {
        Long businessId = businessContext.requireBusinessId(username);
        Product p = productRepository.findByIdAndBusinessId(id, businessId)
                .orElseThrow(() -> new ResourceNotFoundException("Product not found: " + id));
        productRepository.delete(p);
        log.info("[PRODUCT] Deleted id={} for businessId={}", id, businessId);
    }

    // Validates that the category exists and belongs to this business; returns its name.
    private String resolveCategoryName(Long businessId, Long categoryId) {
        if (categoryId == null) return null;
        ProductCategory category = categoryRepository.findByIdAndBusinessId(categoryId, businessId)
                .orElseThrow(() -> new IllegalArgumentException("Kategori tidak ditemukan: " + categoryId));
        return category.getName();
    }

    private String categoryName(Long businessId, Long categoryId) {
        if (categoryId == null) return null;
        return categoryRepository.findByIdAndBusinessId(categoryId, businessId)
                .map(ProductCategory::getName)
                .orElse(null);
    }

    private Map<Long, String> categoryNames(Long businessId) {
        return categoryRepository.findByBusinessIdOrderByNameAsc(businessId).stream()
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
