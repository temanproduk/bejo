package com.bejo.service;

import com.bejo.constant.Acquirer;
import com.bejo.constant.StockMutationType;
import com.bejo.constant.TransactionStatus;
import com.bejo.constant.TransactionType;
import com.bejo.dto.request.AddTransactionRequestDto;
import com.bejo.dto.request.BulkAddTransactionRequestDto;
import com.bejo.dto.request.BulkTransactionItemDto;
import com.bejo.dto.request.TransactionItemRequestDto;
import com.bejo.dto.request.UpdateTransactionRequestDto;
import com.bejo.dto.response.BulkTransactionResultResponseDto;
import com.bejo.dto.response.TransactionCreatedResponseDto;
import com.bejo.dto.response.TransactionItemResponseDto;
import com.bejo.dto.response.TransactionResponseDto;
import com.bejo.dto.response.TransactionSummaryResponseDto;
import com.bejo.entity.Business;
import com.bejo.entity.Product;
import com.bejo.entity.Transaction;
import com.bejo.entity.TransactionItem;
import com.bejo.exception.ResourceNotFoundException;
import com.bejo.repository.BusinessRepository;
import com.bejo.repository.ProductRepository;
import com.bejo.repository.TransactionItemRepository;
import com.bejo.repository.TransactionRepository;
import com.bejo.util.AppTime;
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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
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
    private final BusinessContext businessContext;
    private final Validator validator;
    private final Counter transactionFetchCounter;

    public TransactionService(TransactionRepository transactionRepository,
                              TransactionItemRepository transactionItemRepository,
                              ProductRepository productRepository,
                              StockMutationService stockMutationService,
                              BusinessRepository businessRepository,
                              BusinessContext businessContext,
                              Validator validator,
                              MeterRegistry meterRegistry) {
        this.transactionRepository = transactionRepository;
        this.transactionItemRepository = transactionItemRepository;
        this.productRepository = productRepository;
        this.stockMutationService = stockMutationService;
        this.businessRepository = businessRepository;
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

        Transaction t = buildTransaction(businessId, idempotencyKey, request);
        transactionRepository.save(t);
        // Optional line items: validate stock, reduce it, and snapshot the sold products.
        // Any insufficient stock rolls back the whole transaction (@Transactional).
        applyItems(businessId, t, request.items());
        log.info("[TXN] Created type={} acquirer={} transactionId={}", t.getType(), t.getAcquirer(), t.getTransactionId());
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

        // Pass 2 — reduce stock, snapshot the sold products, record the stock ledger entry
        List<StockMutationService.StockChangeLine> changes = new ArrayList<>();
        long total = 0;
        for (Line line : lines) {
            int previous = line.product.getStock();
            int updated = previous - line.quantity;
            line.product.setStock(updated);
            productRepository.save(line.product);

            long unitPrice = line.product.getPrice();
            long subtotal = unitPrice * line.quantity;
            total += subtotal;
            TransactionItem ti = new TransactionItem();
            ti.setHistoryId(t.getId());
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
        List<TransactionItemResponseDto> items = transactionItemRepository.findByHistoryIdOrderByIdAsc(t.getId()).stream()
                .map(i -> new TransactionItemResponseDto(
                        i.getProductId(), i.getSku(), i.getName(),
                        i.getQuantity(), i.getUnitPrice(), i.getSubtotal()))
                .toList();
        return toDto(t, merchantName(businessId), items.isEmpty() ? null : items);
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
                Transaction t = buildTransaction(businessId, key, item.transaction());
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
    // PARSED requires an explicit acquirer (MANUAL defaults to Cash).
    private Transaction buildTransaction(Long businessId, String idempotencyKey, AddTransactionRequestDto request) {
        boolean isParsed = TransactionType.PARSED.equals(request.type());
        if (isParsed && (request.acquirer() == null || request.acquirer().isBlank())) {
            throw new IllegalArgumentException("acquirer wajib diisi untuk type PARSED");
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
        return t;
    }

    @Transactional
    public TransactionResponseDto update(String username, String transactionId, UpdateTransactionRequestDto request) {
        if (request.isEmpty()) {
            throw new IllegalArgumentException("Tidak ada field yang diubah");
        }
        Long businessId = businessContext.requireBusinessId(username);

        Transaction t = transactionRepository.findByTransactionIdAndBusinessIdAndDeletedAtIsNull(transactionId, businessId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + transactionId));

        // MANAGED rows are pushed by the Bejo server and are the payment record
        // of truth — only MANUAL/PARSED entries are user-editable
        if (!TransactionType.MANUAL.equals(t.getType()) && !TransactionType.PARSED.equals(t.getType())) {
            throw new IllegalArgumentException("Transaksi bertipe " + t.getType() + " tidak dapat diubah");
        }

        if (request.acquirer() != null && request.acquirer().isBlank()) {
            throw new IllegalArgumentException("acquirer tidak boleh kosong");
        }

        if (request.transactionDate() != null) t.setTransactionDate(request.transactionDate());
        if (request.amount() != null) t.setAmount(request.amount());
        if (request.issuerName() != null) t.setSource(request.issuerName());
        if (request.merchantPan() != null) t.setMerchantPan(request.merchantPan());
        if (request.customerPan() != null) t.setCustomerPan(request.customerPan());
        if (request.rrn() != null) t.setRrn(request.rrn());
        if (request.acquirer() != null) t.setAcquirer(request.acquirer());
        transactionRepository.save(t);
        log.info("[TXN] Updated transactionId={} businessId={}", transactionId, businessId);

        return toDto(t, merchantName(businessId));
    }

    @Transactional
    public void delete(String username, String transactionId) {
        Long businessId = businessContext.requireBusinessId(username);
        Transaction t = transactionRepository.findByTransactionIdAndBusinessIdAndDeletedAtIsNull(transactionId, businessId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + transactionId));
        t.setDeletedAt(LocalDateTime.now());
        transactionRepository.save(t);
        log.info("[TXN] Soft-deleted transactionId={} businessId={}", transactionId, businessId);
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
                items
        );
    }
}
