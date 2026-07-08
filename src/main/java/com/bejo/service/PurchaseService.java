package com.bejo.service;

import com.bejo.dto.request.CreatePurchaseRequestDto;
import com.bejo.dto.request.PurchaseItemRequestDto;
import com.bejo.dto.response.PurchaseResponseDto;
import com.bejo.entity.Product;
import com.bejo.entity.Purchase;
import com.bejo.entity.PurchaseItem;
import com.bejo.exception.ResourceNotFoundException;
import com.bejo.repository.ProductRepository;
import com.bejo.repository.PurchaseItemRepository;
import com.bejo.repository.PurchaseRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * Records purchase orders (restocks) that raise the stock of several products at once.
 * A purchase is applied atomically: every line is resolved to one of the merchant's
 * own products first, so if any is unknown the whole purchase is rejected and no stock
 * changes. Line prices are snapshotted (provided {@code unitPrice}, else the product's
 * {@code costPrice}, else 0) so history is stable if the product later changes.
 */
@Service
public class PurchaseService {

    private static final Logger log = LoggerFactory.getLogger(PurchaseService.class);

    private final PurchaseRepository purchaseRepository;
    private final PurchaseItemRepository purchaseItemRepository;
    private final ProductRepository productRepository;

    public PurchaseService(PurchaseRepository purchaseRepository,
                           PurchaseItemRepository purchaseItemRepository,
                           ProductRepository productRepository) {
        this.purchaseRepository = purchaseRepository;
        this.purchaseItemRepository = purchaseItemRepository;
        this.productRepository = productRepository;
    }

    @Transactional
    public PurchaseResponseDto create(String username, CreatePurchaseRequestDto request) {
        log.info("[PURCHASE] username={} lines={}", username, request.items().size());

        // Pass 1 — resolve every line to an owned product before changing anything
        List<Line> lines = new ArrayList<>();
        for (PurchaseItemRequestDto item : request.items()) {
            Product product = productRepository.findByIdAndUsername(item.productId(), username)
                    .orElseThrow(() -> new IllegalArgumentException("Produk tidak ditemukan: " + item.productId()));
            long unitPrice = item.unitPrice() != null ? item.unitPrice()
                    : (product.getCostPrice() != null ? product.getCostPrice() : 0L);
            lines.add(new Line(product, item.quantity(), unitPrice));
        }

        // Pass 2 — raise stock and persist the purchase + its items
        Purchase purchase = new Purchase();
        purchase.setUsername(username);
        purchase.setNote(request.note());
        purchase.setTotalAmount(0);
        purchaseRepository.save(purchase);

        long total = 0;
        List<PurchaseItem> savedItems = new ArrayList<>();
        for (Line line : lines) {
            long subtotal = line.unitPrice * line.quantity;
            total += subtotal;

            line.product.setStock(line.product.getStock() + line.quantity);
            productRepository.save(line.product);

            PurchaseItem item = new PurchaseItem();
            item.setPurchaseId(purchase.getId());
            item.setProductId(line.product.getId());
            item.setSku(line.product.getSku());
            item.setName(line.product.getName());
            item.setUnitPrice(line.unitPrice);
            item.setQuantity(line.quantity);
            item.setSubtotal(subtotal);
            savedItems.add(purchaseItemRepository.save(item));
        }

        purchase.setTotalAmount(total);
        purchaseRepository.save(purchase);

        log.info("[PURCHASE] Recorded id={} username={} lines={} total={}",
                purchase.getId(), username, savedItems.size(), total);
        return toDto(purchase, savedItems);
    }

    @Transactional(readOnly = true)
    public List<PurchaseResponseDto> list(String username) {
        log.info("[PURCHASE] List for username={}", username);
        // Summary only — items omitted to keep the list small
        return purchaseRepository.findByUsernameOrderByCreatedAtDesc(username).stream()
                .map(p -> toDto(p, null))
                .toList();
    }

    @Transactional(readOnly = true)
    public PurchaseResponseDto get(String username, Long id) {
        log.info("[PURCHASE] Get id={} for username={}", id, username);
        Purchase purchase = purchaseRepository.findByIdAndUsername(id, username)
                .orElseThrow(() -> new ResourceNotFoundException("Purchase not found: " + id));
        return toDto(purchase, purchaseItemRepository.findByPurchaseIdOrderByIdAsc(id));
    }

    private PurchaseResponseDto toDto(Purchase p, List<PurchaseItem> items) {
        List<PurchaseResponseDto.PurchaseItemResponseDto> itemDtos = items == null ? null : items.stream()
                .map(i -> new PurchaseResponseDto.PurchaseItemResponseDto(
                        i.getProductId(), i.getSku(), i.getName(), i.getUnitPrice(), i.getQuantity(), i.getSubtotal()))
                .toList();
        return new PurchaseResponseDto(p.getId(), p.getTotalAmount(), p.getNote(), p.getCreatedAt(), itemDtos);
    }

    // Working tuple: resolved product + the line's quantity and snapshotted unit price
    private record Line(Product product, int quantity, long unitPrice) {}
}
