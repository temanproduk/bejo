package com.bejo.commerce.service;
import com.bejo.core.service.BusinessContext;

import com.bejo.commerce.domain.StockMutationType;
import com.bejo.commerce.dto.CreatePurchaseRequestDto;
import com.bejo.commerce.dto.PurchaseItemRequestDto;
import com.bejo.commerce.dto.StockMutationResponseDto;
import com.bejo.commerce.domain.Product;
import com.bejo.commerce.domain.StockMutation;
import com.bejo.commerce.domain.StockMutationItem;
import com.bejo.shared.exception.ResourceNotFoundException;
import com.bejo.commerce.repository.ProductRepository;
import com.bejo.commerce.repository.StockMutationItemRepository;
import com.bejo.commerce.repository.StockMutationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Stock mutation tracker (backed by the {@code purchases}/{@code purchase_items}
 * tables). Every recorded change to product stock becomes a mutation with a
 * {@code type}:
 * <ul>
 *   <li>{@link StockMutationType#PURCHASE} — restock via {@link #create} (raises stock, priced);</li>
 *   <li>{@link StockMutationType#ADJUSTMENT} — stock opname (from {@code ProductService.adjustStock});</li>
 *   <li>{@link StockMutationType#UPDATE} — a product edit that changed stock (from {@code ProductService.update}).</li>
 * </ul>
 * The ADJUSTMENT/UPDATE mutations are recorded by the caller after it has already
 * applied the stock change (via {@link #recordStockChange}); {@link #create} both
 * applies and records the restock. {@code GET /api/stock-mutations} lists all mutations
 * (the stock-change history); {@code GET /api/stock-mutations/{id}} returns one with its
 * line items.
 */
@Service
public class StockMutationService {

    private static final Logger log = LoggerFactory.getLogger(StockMutationService.class);

    private final StockMutationRepository stockMutationRepository;
    private final StockMutationItemRepository stockMutationItemRepository;
    private final ProductRepository productRepository;
    private final BusinessContext businessContext;

    public StockMutationService(StockMutationRepository stockMutationRepository,
                           StockMutationItemRepository stockMutationItemRepository,
                           ProductRepository productRepository,
                           BusinessContext businessContext) {
        this.stockMutationRepository = stockMutationRepository;
        this.stockMutationItemRepository = stockMutationItemRepository;
        this.productRepository = productRepository;
        this.businessContext = businessContext;
    }

    /** Restock (PURCHASE): resolves + raises stock of several products atomically. */
    @Transactional
    public StockMutationResponseDto create(String username, CreatePurchaseRequestDto request) {
        Long businessId = businessContext.requireBusinessId(username);
        log.info("[PURCHASE] businessId={} lines={}", businessId, request.items().size());

        // Pass 1 — resolve every line to an owned product before changing anything
        List<Line> lines = new ArrayList<>();
        for (PurchaseItemRequestDto item : request.items()) {
            Product product = productRepository.findByIdAndBusinessId(item.productId(), businessId)
                    .orElseThrow(() -> new IllegalArgumentException("Produk tidak ditemukan: " + item.productId()));
            long unitPrice = item.unitPrice() != null ? item.unitPrice()
                    : (product.getCostPrice() != null ? product.getCostPrice() : 0L);
            lines.add(new Line(product, item.quantity(), unitPrice));
        }

        // Pass 2 — raise stock and persist the mutation + its items
        StockMutation purchase = newMutation(businessId, StockMutationType.PURCHASE, request.note());
        stockMutationRepository.save(purchase);

        long total = 0;
        List<StockMutationItem> savedItems = new ArrayList<>();
        for (Line line : lines) {
            long subtotal = line.unitPrice * line.quantity;
            total += subtotal;

            int previous = line.product.getStock();
            int updated = previous + line.quantity;
            line.product.setStock(updated);
            productRepository.save(line.product);

            StockMutationItem item = baseItem(purchase.getId(), line.product, previous, updated);
            item.setUnitPrice(line.unitPrice);
            item.setSubtotal(subtotal);
            savedItems.add(stockMutationItemRepository.save(item));
        }

        purchase.setTotalAmount(total);
        stockMutationRepository.save(purchase);

        log.info("[PURCHASE] Recorded id={} businessId={} lines={} total={}",
                purchase.getId(), businessId, savedItems.size(), total);
        return toDto(purchase, savedItems);
    }

    /**
     * Records a non-purchase stock mutation (ADJUSTMENT / UPDATE / SALE). The caller must
     * have already applied the stock change to the products; this only writes the history.
     */
    @Transactional
    public void recordStockChange(Long businessId, String type, String note, List<StockChangeLine> changes) {
        if (changes.isEmpty()) return;
        StockMutation mutation = newMutation(businessId, type, note);
        stockMutationRepository.save(mutation);
        for (StockChangeLine c : changes) {
            stockMutationItemRepository.save(baseItem(mutation.getId(), c.product(), c.previousStock(), c.newStock()));
        }
        log.info("[STOCK-MUT] Recorded type={} id={} businessId={} lines={}",
                type, mutation.getId(), businessId, changes.size());
    }

    @Transactional(readOnly = true)
    public Page<StockMutationResponseDto> list(String username, Pageable pageable) {
        Long businessId = businessContext.requireBusinessId(username);
        log.info("[PURCHASE] List (stock mutations) for businessId={} page={} size={}",
                businessId, pageable.getPageNumber(), pageable.getPageSize());
        Page<StockMutation> mutations = stockMutationRepository.findByBusinessIdOrderByCreatedAtDesc(businessId, pageable);
        if (mutations.isEmpty()) return mutations.map(m -> toDto(m, List.of()));

        // Batch-load the line items for this page's mutations in one query, then group (no N+1)
        List<Long> ids = mutations.getContent().stream().map(StockMutation::getId).toList();
        Map<Long, List<StockMutationItem>> itemsByMutation = stockMutationItemRepository
                .findByMutationIdInOrderByIdAsc(ids).stream()
                .collect(Collectors.groupingBy(StockMutationItem::getMutationId));

        return mutations.map(m -> toDto(m, itemsByMutation.getOrDefault(m.getId(), List.of())));
    }

    @Transactional(readOnly = true)
    public StockMutationResponseDto get(String username, Long id) {
        Long businessId = businessContext.requireBusinessId(username);
        log.info("[PURCHASE] Get stock mutation id={} for businessId={}", id, businessId);
        StockMutation purchase = stockMutationRepository.findByIdAndBusinessId(id, businessId)
                .orElseThrow(() -> new ResourceNotFoundException("Data stok tidak ditemukan: " + id));
        return toDto(purchase, stockMutationItemRepository.findByMutationIdOrderByIdAsc(id));
    }

    private StockMutation newMutation(Long businessId, String type, String note) {
        StockMutation p = new StockMutation();
        p.setBusinessId(businessId);
        p.setType(type);
        p.setNote(note);
        p.setTotalAmount(0);
        return p;
    }

    private StockMutationItem baseItem(Long mutationId, Product product, int previousStock, int newStock) {
        StockMutationItem item = new StockMutationItem();
        item.setMutationId(mutationId);
        item.setProductId(product.getId());
        item.setSku(product.getSku());
        item.setName(product.getName());
        item.setPreviousStock(previousStock);
        item.setNewStock(newStock);
        item.setQuantityChange(newStock - previousStock);
        return item;
    }

    private StockMutationResponseDto toDto(StockMutation p, List<StockMutationItem> items) {
        List<StockMutationResponseDto.StockMutationItemResponseDto> itemDtos = items == null ? null : items.stream()
                .map(i -> new StockMutationResponseDto.StockMutationItemResponseDto(
                        i.getProductId(), i.getSku(), i.getName(),
                        i.getPreviousStock(), i.getNewStock(), i.getQuantityChange(),
                        i.getUnitPrice(), i.getSubtotal()))
                .toList();
        return new StockMutationResponseDto(p.getId(), p.getType(), p.getTotalAmount(), p.getNote(), p.getCreatedAt(), itemDtos);
    }

    // Restock working tuple: resolved product + quantity added + snapshotted unit price
    private record Line(Product product, int quantity, long unitPrice) {}

    // A product's stock change (previous -> new) for ADJUSTMENT / UPDATE mutations
    public record StockChangeLine(Product product, int previousStock, int newStock) {}
}
