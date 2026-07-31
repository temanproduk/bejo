package com.bejo.commerce.service;
import com.bejo.core.service.BusinessContext;

import com.bejo.commerce.domain.Acquirer;
import com.bejo.commerce.domain.StockMutationType;
import com.bejo.commerce.domain.TransactionStatus;
import com.bejo.commerce.domain.TransactionType;
import com.bejo.commerce.dto.AddTransactionRequestDto;
import com.bejo.commerce.dto.BulkAddTransactionRequestDto;
import com.bejo.commerce.dto.BulkTransactionItemDto;
import com.bejo.commerce.dto.TransactionItemRequestDto;
import com.bejo.commerce.dto.UpdateTransactionRequestDto;
import com.bejo.commerce.dto.BulkTransactionResultResponseDto;
import com.bejo.commerce.dto.TransactionCreatedResponseDto;
import com.bejo.commerce.dto.TransactionItemResponseDto;
import com.bejo.commerce.dto.TransactionResponseDto;
import com.bejo.commerce.dto.TransactionSummaryResponseDto;
import com.bejo.core.domain.Business;
import com.bejo.core.domain.BusinessRole;
import com.bejo.core.domain.User;
import com.bejo.core.domain.UserQris;
import com.bejo.commerce.domain.Product;
import com.bejo.commerce.domain.Transaction;
import com.bejo.commerce.domain.TransactionItem;
import com.bejo.commerce.push.TransactionPushPayload;
import com.bejo.notification.PushNotificationService;
import com.bejo.notification.PushPayload;
import com.bejo.shared.exception.ResourceNotFoundException;
import com.bejo.core.repository.BusinessRepository;
import com.bejo.core.repository.UserRepository;
import com.bejo.core.repository.UserQrisRepository;
import com.bejo.commerce.repository.ProductRepository;
import com.bejo.commerce.repository.ProductStock;
import com.bejo.commerce.repository.TransactionItemRepository;
import com.bejo.commerce.repository.TransactionRepository;
import com.bejo.shared.util.AppTime;
import com.bejo.shared.util.Rupiah;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    // Bulk-sync per-item result statuses
    private static final String RESULT_CREATED = "CREATED";
    private static final String RESULT_DUPLICATE = "DUPLICATE";
    private static final String RESULT_FAILED = "FAILED";

    private final TransactionRepository transactionRepository;
    private final TransactionItemRepository transactionItemRepository;
    private final ProductRepository productRepository;
    private final StockMutationService stockMutationService;
    private final BusinessRepository businessRepository;
    private final UserRepository userRepository;
    private final UserQrisRepository userQrisRepository;
    private final PushNotificationService pushNotificationService;
    private final BusinessContext businessContext;
    private final Validator validator;
    private final Counter transactionFetchCounter;

    public TransactionService(TransactionRepository transactionRepository,
                              TransactionItemRepository transactionItemRepository,
                              ProductRepository productRepository,
                              StockMutationService stockMutationService,
                              BusinessRepository businessRepository,
                              UserRepository userRepository,
                              UserQrisRepository userQrisRepository,
                              PushNotificationService pushNotificationService,
                              BusinessContext businessContext,
                              Validator validator,
                              MeterRegistry meterRegistry) {
        this.transactionRepository = transactionRepository;
        this.transactionItemRepository = transactionItemRepository;
        this.productRepository = productRepository;
        this.stockMutationService = stockMutationService;
        this.businessRepository = businessRepository;
        this.userRepository = userRepository;
        this.userQrisRepository = userQrisRepository;
        this.pushNotificationService = pushNotificationService;
        this.businessContext = businessContext;
        this.validator = validator;
        this.transactionFetchCounter = Counter.builder("transactions.fetched").register(meterRegistry);
    }

    public Page<TransactionResponseDto> getHistory(String username, LocalDate dateStart, LocalDate dateEnd, Pageable pageable) {
        Long businessId = businessContext.requireBusinessId(username);
        log.info("[HISTORY] businessId={} dateStart={} dateEnd={} page={} size={}",
                businessId, dateStart, dateEnd, pageable.getPageNumber(), pageable.getPageSize());

        String merchantName = merchantName(businessId);

        LocalDateTime start = dateStart != null ? dateStart.atStartOfDay() : null;
        LocalDateTime end   = dateEnd   != null ? dateEnd.atTime(java.time.LocalTime.MAX) : null;

        Page<Transaction> txns;
        if (start == null && end == null) {
            txns = transactionRepository.findByBusinessIdAndDeletedAtIsNullOrderByTransactionDateDesc(businessId, pageable);
        } else if (start != null && end != null) {
            txns = transactionRepository.findByBusinessIdAndDeletedAtIsNullAndTransactionDateBetweenOrderByTransactionDateDesc(businessId, start, end, pageable);
        } else if (start != null) {
            txns = transactionRepository.findByBusinessIdAndDeletedAtIsNullAndTransactionDateGreaterThanEqualOrderByTransactionDateDesc(businessId, start, pageable);
        } else {
            txns = transactionRepository.findByBusinessIdAndDeletedAtIsNullAndTransactionDateLessThanEqualOrderByTransactionDateDesc(businessId, end, pageable);
        }

        Page<TransactionResponseDto> result = txns.map(t -> toDto(t, merchantName));

        transactionFetchCounter.increment(result.getNumberOfElements());
        log.info("[HISTORY] Returning {} of {} transactions (page {}/{}) for businessId={}",
                result.getNumberOfElements(), result.getTotalElements(),
                result.getNumber() + 1, result.getTotalPages(), businessId);
        return result;
    }

    /**
     * Dashboard totals for {@code referenceDate} (the client's "today", or the server
     * date when null): summed amount + count for that day, the day before, and the
     * whole calendar month it falls in. Ranges are half-open [start, next) on
     * {@code transactionDate} and exclude soft-deleted rows.
     */
    public TransactionSummaryResponseDto summary(String username, LocalDate referenceDate) {
        Long businessId = businessContext.requireBusinessId(username);
        LocalDate today = referenceDate != null ? referenceDate : LocalDate.now(AppTime.ZONE);
        log.info("[SUMMARY] businessId={} date={}", businessId, today);

        LocalDate yesterday = today.minusDays(1);
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate nextMonth = monthStart.plusMonths(1);

        var todaySum = period(businessId, today.atStartOfDay(), today.plusDays(1).atStartOfDay());
        var yesterdaySum = period(businessId, yesterday.atStartOfDay(), today.atStartOfDay());
        var monthSum = period(businessId, monthStart.atStartOfDay(), nextMonth.atStartOfDay());

        return new TransactionSummaryResponseDto(today, todaySum, yesterdaySum, monthSum);
    }

    private TransactionSummaryResponseDto.PeriodSummary period(Long businessId, LocalDateTime start, LocalDateTime end) {
        var agg = transactionRepository.sumBetween(businessId, start, end);
        return new TransactionSummaryResponseDto.PeriodSummary(agg.getTotalAmount(), agg.getCount());
    }

    @Transactional
    public TransactionCreatedResponseDto create(String username, String idempotencyKey, AddTransactionRequestDto request) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header wajib diisi");
        }
        Long businessId = businessContext.requireBusinessId(username);

        // Idempotency check — return existing transactionId if key was already processed
        var existing = transactionRepository.findByIdempotencyKeyAndBusinessId(idempotencyKey, businessId);
        if (existing.isPresent()) {
            log.info("[TXN] Idempotent replay key={} businessId={} transactionId={}", idempotencyKey, businessId, existing.get().getTransactionId());
            return new TransactionCreatedResponseDto(existing.get().getTransactionId());
        }

        Transaction t = buildTransaction(businessId, username, idempotencyKey, request);
        transactionRepository.save(t);
        // Optional line items: validate stock, reduce it, and snapshot the sold products.
        // Any insufficient stock rolls back the whole transaction (@Transactional).
        applyItems(businessId, t, request.items());
        log.info("[TXN] Created type={} acquirer={} transactionId={} createdBy={}",
                t.getType(), t.getAcquirer(), t.getTransactionId(), username);
        // A PARSED transaction is a real-time payment an app detected — notify the super admin
        // plus everyone sharing the QRIS it landed on (see notifyParsedTransaction).
        if (TransactionType.PARSED.equals(t.getType())) {
            notifyParsedTransaction(businessId, t, username);
        }
        return new TransactionCreatedResponseDto(t.getTransactionId());
    }

    /**
     * Records the products sold in a transaction. Two-pass and atomic: pass 1 resolves
     * every line to one of the business's own products and checks it has enough stock
     * (rejecting the whole transaction otherwise); pass 2 reduces the stock, saves the
     * line items (price snapshotted from the product), and records a SALE stock mutation
     * so the stock ledger stays complete. Duplicate product lines are rejected.
     * <p>
     * When items are present the transaction {@code amount} is <b>derived</b> from the sum
     * of the line subtotals (the client-sent amount is overridden), so the recorded total
     * can never disagree with its line items.
     */
    private void applyItems(Long businessId, Transaction t, List<TransactionItemRequestDto> items) {
        if (items == null || items.isEmpty()) return;

        // Pass 1 — resolve + validate stock before changing anything
        Set<Long> seen = new HashSet<>();
        List<Line> lines = new ArrayList<>();
        for (TransactionItemRequestDto item : items) {
            if (!seen.add(item.productId())) {
                throw new IllegalArgumentException("Produk duplikat dalam satu transaksi: " + item.productId());
            }
            Product product = productRepository.findByIdAndBusinessId(item.productId(), businessId)
                    .orElseThrow(() -> new IllegalArgumentException("Produk tidak ditemukan: " + item.productId()));
            if (product.getStock() < item.quantity()) {
                throw new IllegalArgumentException("Stok tidak cukup untuk " + product.getName()
                        + " (tersedia " + product.getStock() + ", diminta " + item.quantity() + ")");
            }
            lines.add(new Line(product, item.quantity()));
        }

        // Pass 2 — draw the stock down atomically. The pass-1 check above gives the friendly
        // error message; this conditional update is what actually makes it safe, refusing the
        // sale if a concurrent one took the stock in between (0 rows changed).
        for (Line line : lines) {
            if (productRepository.decreaseStock(line.product.getId(), businessId, line.quantity) == 0) {
                throw new IllegalArgumentException("Stok tidak cukup untuk " + line.product.getName()
                        + " (stok berubah, coba lagi)");
            }
        }
        // Authoritative post-update stock for the ledger (the loaded entities are now stale)
        Map<Long, Integer> stocks = currentStocks(lines.stream().map(l -> l.product.getId()).toList());

        List<StockMutationService.StockChangeLine> changes = new ArrayList<>();
        long total = 0;
        for (Line line : lines) {
            int updated = stocks.getOrDefault(line.product.getId(), line.product.getStock() - line.quantity);
            int previous = updated + line.quantity;

            long unitPrice = line.product.getPrice();
            long subtotal = unitPrice * line.quantity;
            total += subtotal;
            TransactionItem ti = new TransactionItem();
            ti.setTransactionId(t.getId());
            ti.setProductId(line.product.getId());
            ti.setSku(line.product.getSku());
            ti.setName(line.product.getName());
            ti.setQuantity(line.quantity);
            ti.setUnitPrice(unitPrice);
            ti.setSubtotal(subtotal);
            transactionItemRepository.save(ti);

            changes.add(new StockMutationService.StockChangeLine(line.product, previous, updated));
        }
        // Derive the transaction amount from the line items (override the client-sent value)
        t.setAmount(total);
        transactionRepository.save(t);
        stockMutationService.recordStockChange(businessId, StockMutationType.SALE,
                "Sale " + t.getTransactionId(), changes);
        log.info("[TXN] Sold {} product line(s) total={} for transactionId={}", lines.size(), total, t.getTransactionId());
    }

    // Sale working tuple: resolved product + quantity sold
    private record Line(Product product, int quantity) {}

    @Transactional(readOnly = true)
    public TransactionResponseDto get(String username, String transactionId) {
        Long businessId = businessContext.requireBusinessId(username);
        Transaction t = transactionRepository.findByTransactionIdAndBusinessIdAndDeletedAtIsNull(transactionId, businessId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + transactionId));
        List<TransactionItemResponseDto> items = itemDtos(t.getId());
        return toDto(t, merchantName(businessId), items.isEmpty() ? null : items);
    }

    private List<TransactionItemResponseDto> itemDtos(Long historyId) {
        return transactionItemRepository.findByTransactionIdOrderByIdAsc(historyId).stream()
                .map(i -> new TransactionItemResponseDto(
                        i.getProductId(), i.getSku(), i.getName(),
                        i.getQuantity(), i.getUnitPrice(), i.getSubtotal()))
                .toList();
    }

    /**
     * Bulk-create manually-entered transactions synced from the client after offline
     * entry. Each item is processed independently (its own DB transaction), so a
     * malformed or duplicate row never blocks the rest of the batch. Line items are not
     * processed for bulk sync (offline stock validation is out of scope).
     */
    public BulkTransactionResultResponseDto bulkCreate(String username, BulkAddTransactionRequestDto request) {
        Long businessId = businessContext.requireBusinessId(username);
        List<BulkTransactionItemDto> items = request.transactions();
        log.info("[TXN-BULK] businessId={} items={}", businessId, items.size());

        List<BulkTransactionResultResponseDto.ItemResult> results = new ArrayList<>();
        int created = 0, duplicate = 0, failed = 0;

        for (BulkTransactionItemDto item : items) {
            String key = item.idempotencyKey();

            // Per-item validation so one bad row fails alone instead of the whole batch
            Set<ConstraintViolation<BulkTransactionItemDto>> violations = validator.validate(item);
            if (!violations.isEmpty()) {
                failed++;
                results.add(failedResult(key, violations.stream()
                        .map(ConstraintViolation::getMessage)
                        .collect(Collectors.joining(", "))));
                continue;
            }

            try {
                var existing = transactionRepository.findByIdempotencyKeyAndBusinessId(key, businessId);
                if (existing.isPresent()) {
                    duplicate++;
                    results.add(new BulkTransactionResultResponseDto.ItemResult(
                            key, RESULT_DUPLICATE, existing.get().getTransactionId(), null));
                    continue;
                }
                Transaction t = buildTransaction(businessId, username, key, item.transaction());
                transactionRepository.save(t);
                created++;
                results.add(new BulkTransactionResultResponseDto.ItemResult(
                        key, RESULT_CREATED, t.getTransactionId(), null));
            } catch (DataIntegrityViolationException e) {
                // Concurrent insert with the same idempotency key — treat as already synced
                duplicate++;
                String existingId = transactionRepository.findByIdempotencyKeyAndBusinessId(key, businessId)
                        .map(Transaction::getTransactionId).orElse(null);
                results.add(new BulkTransactionResultResponseDto.ItemResult(
                        key, RESULT_DUPLICATE, existingId, null));
            } catch (IllegalArgumentException e) {
                failed++;
                results.add(failedResult(key, e.getMessage()));
            }
        }

        log.info("[TXN-BULK] Done businessId={} created={} duplicate={} failed={}",
                businessId, created, duplicate, failed);
        return new BulkTransactionResultResponseDto(items.size(), created, duplicate, failed, results);
    }

    private BulkTransactionResultResponseDto.ItemResult failedResult(String key, String error) {
        return new BulkTransactionResultResponseDto.ItemResult(key, RESULT_FAILED, null, error);
    }

    // Builds an unsaved MANUAL/PARSED transaction, applying the cross-field rule that
    // PARSED requires an explicit acquirer (MANUAL defaults to Cash). createdBy snapshots the
    // account/sub-account that entered it (username).
    private Transaction buildTransaction(Long businessId, String username, String idempotencyKey, AddTransactionRequestDto request) {
        boolean isParsed = TransactionType.PARSED.equals(request.type());
        if (isParsed && (request.acquirer() == null || request.acquirer().isBlank())) {
            throw new IllegalArgumentException("Metode pembayaran wajib diisi");
        }
        String acquirer = isParsed ? request.acquirer() : Acquirer.CASH;
        String transactionId = "TXN-" + UUID.randomUUID().toString().replace("-", "").substring(0, 12).toUpperCase();

        Transaction t = new Transaction();
        t.setTransactionId(transactionId);
        t.setIdempotencyKey(idempotencyKey);
        t.setTransactionDate(request.transactionDate());
        t.setAmount(request.amount());
        t.setSource(request.issuerName());
        t.setBusinessId(businessId);
        t.setMerchantPan(request.merchantPan());
        t.setCustomerPan(request.customerPan());
        t.setRrn(request.rrn());
        t.setType(request.type());
        t.setAcquirer(acquirer);
        t.setStatus(TransactionStatus.SUCCESS);
        t.setScrapeDate(LocalDateTime.now());
        t.setCreatedBy(username);
        return t;
    }

    @Transactional
    public TransactionResponseDto update(String username, String transactionId, UpdateTransactionRequestDto request) {
        if (request.isEmpty()) {
            throw new IllegalArgumentException("Tidak ada data yang diubah");
        }
        Long businessId = businessContext.requireBusinessId(username);

        Transaction t = transactionRepository.findByTransactionIdAndBusinessIdAndDeletedAtIsNull(transactionId, businessId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + transactionId));

        // MANAGED rows are pushed by the Bejo server and are the payment record
        // of truth — only MANUAL/PARSED entries are user-editable
        if (!TransactionType.MANUAL.equals(t.getType()) && !TransactionType.PARSED.equals(t.getType())) {
            throw new IllegalArgumentException("Transaksi ini tidak dapat diubah");
        }

        if (request.acquirer() != null && request.acquirer().isBlank()) {
            throw new IllegalArgumentException("Metode pembayaran wajib diisi");
        }

        if (request.transactionDate() != null) t.setTransactionDate(request.transactionDate());
        if (request.amount() != null) t.setAmount(request.amount());
        if (request.issuerName() != null) t.setSource(request.issuerName());
        if (request.merchantPan() != null) t.setMerchantPan(request.merchantPan());
        if (request.customerPan() != null) t.setCustomerPan(request.customerPan());
        if (request.rrn() != null) t.setRrn(request.rrn());
        if (request.acquirer() != null) t.setAcquirer(request.acquirer());
        // Optional line-item replacement — reconciles stock and (when non-empty) re-derives
        // the amount, so it runs after the scalar amount is applied.
        if (request.items() != null) reconcileItems(businessId, t, request.items());
        t.setUpdatedBy(username);
        transactionRepository.save(t);
        log.info("[TXN] Updated transactionId={} businessId={} updatedBy={}", transactionId, businessId, username);

        List<TransactionItemResponseDto> items = itemDtos(t.getId());
        return toDto(t, merchantName(businessId), items.isEmpty() ? null : items);
    }

    /**
     * Replaces a transaction's line items with {@code newItems} and reconciles product
     * stock by the per-product <b>delta</b> against the existing items: a product sold in
     * greater quantity draws its stock further down (validated), a reduced/removed line
     * returns stock. Atomic and two-pass — an unknown product (referenced by the new list),
     * a duplicate product line, or insufficient stock for an increase rejects the whole edit
     * before anything changes. When {@code newItems} is non-empty the transaction amount is
     * re-derived from the line subtotals; an empty list clears the items (returning all their
     * stock) and leaves the amount as set by the scalar update. A single SALE stock mutation
     * records the net changes.
     */
    private void reconcileItems(Long businessId, Transaction t, List<TransactionItemRequestDto> newItems) {
        // Old quantities per product (from the current line items)
        Map<Long, Integer> oldQty = new LinkedHashMap<>();
        List<TransactionItem> existing = transactionItemRepository.findByTransactionIdOrderByIdAsc(t.getId());
        for (TransactionItem it : existing) oldQty.merge(it.getProductId(), it.getQuantity(), Integer::sum);

        // New quantities per product (reject duplicate product lines)
        Map<Long, Integer> newQty = new LinkedHashMap<>();
        for (TransactionItemRequestDto item : newItems) {
            if (newQty.putIfAbsent(item.productId(), item.quantity()) != null) {
                throw new IllegalArgumentException("Produk duplikat dalam satu transaksi: " + item.productId());
            }
        }

        Set<Long> allIds = new LinkedHashSet<>();
        allIds.addAll(oldQty.keySet());
        allIds.addAll(newQty.keySet());

        // Pass 1 — resolve owned products + validate stock for increases (nothing changes yet)
        Map<Long, Product> products = new HashMap<>();
        for (Long pid : allIds) {
            int wanted = newQty.getOrDefault(pid, 0);
            Product product = productRepository.findByIdAndBusinessId(pid, businessId).orElse(null);
            if (product == null) {
                // A product in the NEW list must exist; a removed line whose product was since
                // deleted is just dropped (its stock can't be returned).
                if (wanted > 0) throw new IllegalArgumentException("Produk tidak ditemukan: " + pid);
                continue;
            }
            int delta = wanted - oldQty.getOrDefault(pid, 0);   // >0 = sell more → stock down
            if (delta > 0 && product.getStock() < delta) {
                throw new IllegalArgumentException("Stok tidak cukup untuk " + product.getName()
                        + " (tersedia " + product.getStock() + ", diminta tambahan " + delta + ")");
            }
            products.put(pid, product);
        }

        // Pass 2 — apply the stock deltas atomically (see applyItems), then rebuild the line items
        Map<Long, Integer> applied = new LinkedHashMap<>();
        for (Long pid : allIds) {
            Product product = products.get(pid);
            if (product == null) continue;      // removed line whose product is gone
            int delta = newQty.getOrDefault(pid, 0) - oldQty.getOrDefault(pid, 0);
            if (delta == 0) continue;
            if (delta > 0) {
                if (productRepository.decreaseStock(pid, businessId, delta) == 0) {
                    throw new IllegalArgumentException("Stok tidak cukup untuk " + product.getName()
                            + " (stok berubah, coba lagi)");
                }
            } else {
                productRepository.increaseStock(pid, businessId, -delta);
            }
            applied.put(pid, delta);
        }
        Map<Long, Integer> stocks = currentStocks(applied.keySet());

        List<StockMutationService.StockChangeLine> changes = new ArrayList<>();
        applied.forEach((pid, delta) -> {
            Product product = products.get(pid);
            int updated = stocks.getOrDefault(pid, product.getStock() - delta);
            changes.add(new StockMutationService.StockChangeLine(product, updated + delta, updated));
        });

        transactionItemRepository.deleteAll(existing);
        long total = 0;
        for (TransactionItemRequestDto item : newItems) {
            Product product = products.get(item.productId());
            long unitPrice = product.getPrice();
            long subtotal = unitPrice * item.quantity();
            total += subtotal;
            TransactionItem ti = new TransactionItem();
            ti.setTransactionId(t.getId());
            ti.setProductId(product.getId());
            ti.setSku(product.getSku());
            ti.setName(product.getName());
            ti.setQuantity(item.quantity());
            ti.setUnitPrice(unitPrice);
            ti.setSubtotal(subtotal);
            transactionItemRepository.save(ti);
        }
        // Derive amount from items when present; an empty list keeps the scalar-updated amount
        if (!newItems.isEmpty()) t.setAmount(total);

        if (!changes.isEmpty()) {
            stockMutationService.recordStockChange(businessId, StockMutationType.SALE,
                    "Sale edit " + t.getTransactionId(), changes);
        }
        log.info("[TXN] Reconciled items for transactionId={}: {} line(s), {} stock change(s)",
                t.getTransactionId(), newItems.size(), changes.size());
    }

    @Transactional
    public void delete(String username, String transactionId) {
        // Deleting a transaction is super-admin-only — a sub-account (MEMBER) gets 403.
        Long businessId = businessContext.requireSuperAdminBusinessId(username);
        Transaction t = transactionRepository.findByTransactionIdAndBusinessIdAndDeletedAtIsNull(transactionId, businessId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + transactionId));
        // Return the sold stock to its products (a deleted sale un-sells its items). The item
        // rows are kept (soft delete) as the historical record; stock is credited back once —
        // an already-deleted transaction can't be deleted again, so there's no double-return.
        restoreStockFromItems(businessId, t);
        t.setDeletedAt(LocalDateTime.now());
        transactionRepository.save(t);
        log.info("[TXN] Soft-deleted transactionId={} businessId={}", transactionId, businessId);
    }

    // Credits each line item's quantity back to its product's stock (best-effort per product —
    // a product deleted since the sale is skipped) and records one SALE reversal mutation.
    private void restoreStockFromItems(Long businessId, Transaction t) {
        List<TransactionItem> items = transactionItemRepository.findByTransactionIdOrderByIdAsc(t.getId());
        if (items.isEmpty()) return;
        List<StockMutationService.StockChangeLine> changes = new ArrayList<>();
        Map<Long, Product> restored = new LinkedHashMap<>();
        Map<Long, Integer> returnedQty = new LinkedHashMap<>();
        for (TransactionItem item : items) {
            Product product = productRepository.findByIdAndBusinessId(item.getProductId(), businessId).orElse(null);
            if (product == null) continue;   // product deleted — can't return its stock
            // Atomic credit back, so a concurrent sale of the same product isn't overwritten
            productRepository.increaseStock(product.getId(), businessId, item.getQuantity());
            restored.put(product.getId(), product);
            returnedQty.merge(product.getId(), item.getQuantity(), Integer::sum);
        }
        Map<Long, Integer> stocks = currentStocks(restored.keySet());
        restored.forEach((pid, product) -> {
            int qty = returnedQty.getOrDefault(pid, 0);
            int updated = stocks.getOrDefault(pid, product.getStock() + qty);
            changes.add(new StockMutationService.StockChangeLine(product, updated - qty, updated));
        });
        if (!changes.isEmpty()) {
            stockMutationService.recordStockChange(businessId, StockMutationType.SALE,
                    "Sale delete " + t.getTransactionId(), changes);
            log.info("[TXN] Returned stock for {} line(s) on delete of transactionId={}", changes.size(), t.getTransactionId());
        }
    }

    /**
     * Notify the relevant accounts of a newly-recorded PARSED transaction. A PARSED entry is a
     * real-time payment an app detected off a QRIS merchant notification; the detecting device
     * ({@code creator}) already played the alert locally before calling the API and the client
     * dedupes, so the detector is still included here (it won't double-play).
     *
     * <p>Recipients = the business <b>super admin</b> (always — merchant oversight) plus everyone
     * sharing the QRIS the payment landed on:
     * <ul>
     *   <li>created by the super admin (or any account on the <b>shared</b> business QRIS) → the
     *       super admin + all members <b>without</b> their own {@code user_qris} (the shared-QRIS
     *       cohort);</li>
     *   <li>created by a member with their <b>own</b> QRIS (a separate merchant app) → the super
     *       admin + just that member (no one else shares that QRIS).</li>
     * </ul>
     * Recipients are resolved now (fast DB reads) but the push is deferred to after the transaction
     * commits so a rollback never buzzes a phone; best-effort — a send failure is logged, never rethrown.
     */
    private void notifyParsedTransaction(Long businessId, Transaction t, String creator) {
        Set<String> withOwnQris = userQrisRepository.findByBusinessId(businessId).stream()
                .map(UserQris::getUsername)
                .collect(Collectors.toSet());

        Set<String> recipients = new LinkedHashSet<>();
        // The super admin always gets it (oversight of every sale).
        userRepository.findFirstByBusinessIdAndBusinessRoleAndDeletedAtIsNull(businessId, BusinessRole.SUPER_ADMIN)
                .map(User::getUsername)
                .ifPresent(recipients::add);

        if (withOwnQris.contains(creator)) {
            // Payment on the creator's own QRIS — only the creator shares it (besides the admin).
            recipients.add(creator);
        } else {
            // Payment on the shared business QRIS — everyone on it (members without their own QRIS).
            userRepository.findByBusinessIdAndBusinessRoleAndDeletedAtIsNullOrderByCreatedAtAsc(businessId, BusinessRole.MEMBER)
                    .stream()
                    .map(User::getUsername)
                    .filter(u -> !withOwnQris.contains(u))
                    .forEach(recipients::add);
        }
        if (recipients.isEmpty()) return;

        PushPayload payload = new TransactionPushPayload(t, merchantName(businessId),
                "Pembayaran Diterima", "Pembayaran " + Rupiah.format(t.getAmount()) + " diterima");
        List<String> targets = List.copyOf(recipients);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            sendParsedPushQuietly(targets, payload, t.getTransactionId());
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                sendParsedPushQuietly(targets, payload, t.getTransactionId());
            }
        });
    }

    private void sendParsedPushQuietly(List<String> recipients, PushPayload payload, String transactionId) {
        try {
            int queued = pushNotificationService.sendToUsernames(recipients, payload);
            log.info("[TXN] Notified {} shared-QRIS employee(s) of PARSED transactionId={} ({} push job(s) queued)",
                    recipients.size(), transactionId, queued);
        } catch (RuntimeException e) {
            log.warn("[TXN] Employee notification failed for PARSED transactionId={} (transaction still recorded): {}",
                    transactionId, e.getMessage());
        }
    }

    /**
     * Reads the products' stock back from the database after an atomic update, so the ledger
     * records the real before/after rather than the stale values on the loaded entities.
     */
    private Map<Long, Integer> currentStocks(Collection<Long> productIds) {
        if (productIds.isEmpty()) return Map.of();
        return productRepository.findStockByIdIn(productIds).stream()
                .collect(Collectors.toMap(ProductStock::getId, ProductStock::getStock));
    }

    private String merchantName(Long businessId) {
        return businessRepository.findById(businessId).map(Business::getName).orElse(null);
    }

    // List/update responses omit line items (null → not serialized)
    private TransactionResponseDto toDto(Transaction t, String merchantName) {
        return toDto(t, merchantName, null);
    }

    private TransactionResponseDto toDto(Transaction t, String merchantName, List<TransactionItemResponseDto> items) {
        return new TransactionResponseDto(
                t.getTransactionId(),
                t.getTransactionDate(),
                t.getAmount(),
                t.getType(),
                t.getAcquirer(),
                t.getSource(),
                merchantName,
                t.getMerchantPan(),
                t.getCustomerPan(),
                t.getRrn(),
                t.getCreatedBy(),
                t.getUpdatedBy(),
                items
        );
    }
}
