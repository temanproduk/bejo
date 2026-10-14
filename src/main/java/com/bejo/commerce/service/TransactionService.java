package com.bejo.commerce.service;
import com.bejo.core.service.BusinessContext;

import com.bejo.commerce.domain.Acquirer;
import com.bejo.commerce.domain.FundSource;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TransactionService {

    private static final Logger log = LoggerFactory.getLogger(TransactionService.class);

    // How many waiting transactions a payment tries before giving up (each miss is a lost race for the row)
    private static final int SETTLE_ATTEMPTS = 3;

    // Statuses that are not income: a sale still waiting for its payment, and one that never got paid
    private static final List<String> NOT_INCOME = List.of(TransactionStatus.AWAITING_PAYMENT, TransactionStatus.CANCELLED);

    // Day-end sweep: how many stale sales are cancelled per round trip to the database
    private static final int EXPIRY_BATCH = 200;

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
    private final TransactionTemplate transactionTemplate;
    private final Counter transactionFetchCounter;

    // The day on which the day-end sweep last ran to completion on this instance (see expireStaleAwaitingOncePerDay)
    private volatile LocalDate lastExpirySweep;

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
                              PlatformTransactionManager transactionManager,
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
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.transactionFetchCounter = Counter.builder("transactions.fetched").register(meterRegistry);
    }

    public Page<TransactionResponseDto> getHistory(String username, LocalDate dateStart, LocalDate dateEnd, Pageable pageable) {
        Long businessId = businessContext.requireBusinessId(username);
        expireStaleAwaitingOncePerDay();   // so a sale left over from yesterday is never listed as still waiting
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
     * {@code transactionDate} and exclude soft-deleted rows. A transaction still awaiting
     * payment, or cancelled, is not counted — a waiting one becomes income when it is paid.
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
        var agg = transactionRepository.sumBetween(businessId, start, end, NOT_INCOME);
        return new TransactionSummaryResponseDto.PeriodSummary(agg.getTotalAmount(), agg.getCount());
    }

    @Transactional
    public TransactionCreatedResponseDto create(String username, String idempotencyKey,
                                                String reportingDeviceId, AddTransactionRequestDto request) {
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
        // A PARSED payment that was merged into an awaiting transaction has no row (so no idempotency key
        // of its own): the same request retried must answer with that transaction, not settle another one.
        var settledBefore = transactionRepository.findByPaymentReferenceAndBusinessId(idempotencyKey, businessId);
        if (settledBefore.isPresent()) {
            log.info("[TXN] Idempotent replay of settling payment key={} businessId={} transactionId={}",
                    idempotencyKey, businessId, settledBefore.get().getTransactionId());
            return new TransactionCreatedResponseDto(settledBefore.get().getTransactionId());
        }

        // A payment the app detected for the exact amount of a QRIS sale that is waiting to be paid is
        // that sale's payment: it settles the waiting transaction instead of being recorded a second time
        // (which would count the money twice). Nothing waiting for that amount → recorded as a new row.
        if (TransactionType.PARSED.equals(request.type()) && (request.items() == null || request.items().isEmpty())) {
            requireAcquirerForParsed(request);
            Transaction settled = settleAwaitingPayment(businessId, idempotencyKey, request.amount());
            if (settled != null) {
                notifyParsedTransaction(businessId, settled, username, reportingDeviceId);
                return new TransactionCreatedResponseDto(settled.getTransactionId());
            }
        }

        Transaction t = buildTransaction(businessId, username, idempotencyKey, request);
        transactionRepository.save(t);
        // Optional line items: validate stock, reduce it, and snapshot the sold products.
        // Any insufficient stock rolls back the whole transaction (@Transactional).
        applyItems(businessId, t, request.items());
        log.info("[TXN] Created type={} acquirer={} transactionId={} createdBy={}",
                t.getType(), t.getAcquirer(), t.getTransactionId(), username);
        // A PARSED transaction is a payment this device detected and self-reported — notify the
        // others sharing that QRIS, but not the reporting device itself (see notifyParsedTransaction).
        if (TransactionType.PARSED.equals(t.getType())) {
            notifyParsedTransaction(businessId, t, username, reportingDeviceId);
        }
        return new TransactionCreatedResponseDto(t.getTransactionId());
    }

    /**
     * Settles the oldest transaction awaiting payment for exactly {@code amount}: status → SUCCESS,
     * {@code paidAt} = now, {@code paymentReference} = the payment's key. Returns it, or null when nothing is
     * waiting for that amount. The status change is one conditional update, so two payments (or a payment and
     * a manual "Sudah Terbayar") never settle the same row; losing that race just moves on to the next one.
     */
    private Transaction settleAwaitingPayment(Long businessId, String paymentReference, Long amount) {
        for (int attempt = 0; attempt < SETTLE_ATTEMPTS; attempt++) {
            Optional<Transaction> waiting = transactionRepository
                    .findFirstByBusinessIdAndDeletedAtIsNullAndStatusAndAmountOrderByTransactionDateAscIdAsc(
                            businessId, TransactionStatus.AWAITING_PAYMENT, amount);
            if (waiting.isEmpty()) return null;
            Long id = waiting.get().getId();
            int changed = transactionRepository.settleAwaiting(id, businessId, TransactionStatus.AWAITING_PAYMENT,
                    TransactionStatus.SUCCESS, LocalDateTime.now(), paymentReference);
            if (changed == 1) {
                Transaction settled = transactionRepository.findById(id).orElseThrow();
                log.info("[TXN] Payment of {} settled awaiting transactionId={} businessId={}",
                        amount, settled.getTransactionId(), businessId);
                return settled;
            }
        }
        return null;
    }

    /**
     * Marks a QRIS sale that is waiting for payment as paid, by hand (the payment was not detected by the
     * app, or came another way). Open to any user of the business, like editing. Repeating it on a
     * transaction that was already paid this way just returns it; one that was never awaiting payment is a 400.
     */
    @Transactional
    public TransactionResponseDto markPaid(String username, String transactionId) {
        Long businessId = businessContext.requireBusinessId(username);
        Transaction t = transactionRepository.findByTransactionIdAndBusinessIdAndDeletedAtIsNull(transactionId, businessId)
                .orElseThrow(() -> new ResourceNotFoundException("Transaction not found: " + transactionId));

        if (!TransactionStatus.AWAITING_PAYMENT.equals(t.getStatus())) {
            if (TransactionStatus.SUCCESS.equals(t.getStatus()) && t.getPaidAt() != null) {
                return toDto(t, merchantName(businessId), itemsOrNull(t.getId()));   // already paid: a repeated tap
            }
            if (TransactionStatus.CANCELLED.equals(t.getStatus())) {
                throw new IllegalArgumentException("Transaksi ini sudah dibatalkan");
            }
            throw new IllegalArgumentException("Transaksi ini tidak menunggu pembayaran");
        }

        Long id = t.getId();
        int changed = transactionRepository.settleAwaiting(id, businessId, TransactionStatus.AWAITING_PAYMENT,
                TransactionStatus.SUCCESS, LocalDateTime.now(), null);
        t = transactionRepository.findById(id).orElseThrow();
        if (changed == 1) {
            t.setUpdatedBy(username);
            transactionRepository.save(t);
            log.info("[TXN] Marked transactionId={} businessId={} as paid by {}", transactionId, businessId, username);
        }   // else a payment settled it a moment ago — the reload above already shows that
        return toDto(t, merchantName(businessId), itemsOrNull(id));
    }

    /**
     * End of day for QRIS sales that never got paid: cancels every active transaction still
     * {@code AWAITING_PAYMENT} that is dated before today (WIB) and returns its sold stock. Run by
     * {@link AwaitingPaymentExpiryJob} just after midnight, and lazily by {@link #getHistory} (see
     * {@link #expireStaleAwaitingOncePerDay}). Safe to run twice or on two instances: each sale is cancelled by
     * a conditional update that only fires while it is still waiting, so a payment that settles it first wins.
     * Each sale is its own database transaction, so one failure never holds up or undoes the others.
     *
     * @return how many sales were cancelled
     */
    public int expireStaleAwaiting() {
        LocalDate today = LocalDate.now(AppTime.ZONE);
        LocalDateTime cutoff = today.atStartOfDay();
        int cancelled = 0;
        while (true) {
            List<Long> ids = transactionRepository.findIdsByStatusAndTransactionDateBefore(
                    TransactionStatus.AWAITING_PAYMENT, cutoff, PageRequest.of(0, EXPIRY_BATCH));
            int cancelledThisRound = 0;
            for (Long id : ids) {
                try {
                    if (Boolean.TRUE.equals(transactionTemplate.execute(status -> cancelOneAwaiting(id)))) {
                        cancelledThisRound++;
                    }
                } catch (RuntimeException e) {
                    log.warn("[TXN] Could not cancel stale awaiting transaction id={}: {}", id, e.getMessage());
                }
            }
            cancelled += cancelledThisRound;
            // A short round is the end of the list; a round that cancelled nothing would only find the same rows again
            if (ids.size() < EXPIRY_BATCH || cancelledThisRound == 0) break;
        }
        lastExpirySweep = today;
        if (cancelled > 0) log.info("[TXN] Day-end: cancelled {} sale(s) that were still awaiting payment", cancelled);
        return cancelled;
    }

    /**
     * The same sweep, at most once a day per instance, on the first history request of the day. The scheduled
     * job is the normal way a sale gets cancelled, but on Cloud Run the CPU is throttled between requests, so
     * the job can fire late; this makes sure nobody is shown yesterday's sale as still waiting. Never fails the request.
     */
    private void expireStaleAwaitingOncePerDay() {
        if (LocalDate.now(AppTime.ZONE).equals(lastExpirySweep)) return;
        try {
            expireStaleAwaiting();
        } catch (RuntimeException e) {
            log.warn("[TXN] Day-end sweep failed: {}", e.getMessage());
        }
    }

    private boolean cancelOneAwaiting(Long id) {
        int changed = transactionRepository.cancelAwaiting(id, TransactionStatus.AWAITING_PAYMENT, TransactionStatus.CANCELLED);
        if (changed == 0) return false;   // paid, deleted or cancelled in the meantime
        Transaction t = transactionRepository.findById(id).orElseThrow();
        // The sale did not happen: its products go back on the shelf, like a deleted sale's
        restoreStockFromItems(t.getBusinessId(), t, "Sale cancel ");
        log.info("[TXN] Cancelled unpaid transactionId={} businessId={}", t.getTransactionId(), t.getBusinessId());
        return true;
    }

    private List<TransactionItemResponseDto> itemsOrNull(Long id) {
        List<TransactionItemResponseDto> items = itemDtos(id);
        return items.isEmpty() ? null : items;
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
        requireAcquirerForParsed(request);
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
        // A QRIS sale is entered before the customer has paid; everything else is paid already
        t.setStatus(!isParsed && FundSource.isQris(request.issuerName())
                ? TransactionStatus.AWAITING_PAYMENT : TransactionStatus.SUCCESS);
        t.setScrapeDate(LocalDateTime.now());
        t.setCreatedBy(username);
        return t;
    }

    private void requireAcquirerForParsed(AddTransactionRequestDto request) {
        if (TransactionType.PARSED.equals(request.type())
                && (request.acquirer() == null || request.acquirer().isBlank())) {
            throw new IllegalArgumentException("Metode pembayaran wajib diisi");
        }
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

        // A cancelled sale has had its stock returned; editing its items now would take that stock a second time
        if (TransactionStatus.CANCELLED.equals(t.getStatus())) {
            throw new IllegalArgumentException("Transaksi ini sudah dibatalkan dan tidak dapat diubah");
        }

        if (request.acquirer() != null && request.acquirer().isBlank()) {
            throw new IllegalArgumentException("Metode pembayaran wajib diisi");
        }

        if (request.transactionDate() != null) t.setTransactionDate(request.transactionDate());
        if (request.amount() != null) t.setAmount(request.amount());
        if (request.issuerName() != null) {
            t.setSource(request.issuerName());
            // A sale waiting for a QRIS payment that is now said to be paid another way (cash, transfer, ...)
            // has been paid: settle it. The reverse is not done — a paid transaction never goes back to waiting.
            if (TransactionStatus.AWAITING_PAYMENT.equals(t.getStatus()) && !FundSource.isQris(request.issuerName())) {
                t.setStatus(TransactionStatus.SUCCESS);
                t.setPaidAt(LocalDateTime.now());
            }
        }
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
        // (A cancelled sale already returned its stock when it was cancelled — not again.)
        if (!TransactionStatus.CANCELLED.equals(t.getStatus())) {
            restoreStockFromItems(businessId, t, "Sale delete ");
        }
        t.setDeletedAt(LocalDateTime.now());
        transactionRepository.save(t);
        log.info("[TXN] Soft-deleted transactionId={} businessId={}", transactionId, businessId);
    }

    // Credits each line item's quantity back to its product's stock (best-effort per product —
    // a product deleted since the sale is skipped) and records one SALE reversal mutation.
    private void restoreStockFromItems(Long businessId, Transaction t, String note) {
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
                    note + t.getTransactionId(), changes);
            log.info("[TXN] Returned stock for {} line(s) ({}) of transactionId={}", changes.size(), note.trim(), t.getTransactionId());
        }
    }

    /**
     * Notify the relevant accounts of a newly-recorded PARSED transaction — a real-time payment an
     * app detected off a QRIS merchant notification and self-reported here.
     *
     * <p><b>The reporting device is excluded.</b> It already announced the payment locally before
     * calling the API, so pushing it back is pure echo. This is the one difference from a MANAGED
     * callback ({@code QrisCallbackService}), where the server learns of the payment first and so
     * must tell everyone including the super admin.
     *
     * <p>Recipients = the business <b>super admin</b> (merchant oversight) plus everyone sharing
     * the QRIS the payment landed on, <em>minus the creator</em>:
     * <ul>
     *   <li>created by the super admin (or any account on the <b>shared</b> business QRIS) → the
     *       members <b>without</b> their own {@code user_qris} (the shared-QRIS cohort); the super
     *       admin drops out when they are the reporter, which is the usual case;</li>
     *   <li>created by a member with their <b>own</b> QRIS (a separate merchant app) → just the
     *       super admin, since no one else shares that QRIS.</li>
     * </ul>
     * With a solo super admin reporting their own sale there is nobody left to tell, and no push
     * is sent.
     * Recipients are resolved now (fast DB reads) but the push is deferred to after the transaction
     * commits so a rollback never buzzes a phone; best-effort — a send failure is logged, never rethrown.
     */
    private void notifyParsedTransaction(Long businessId, Transaction t, String creator,
                                         String reportingDeviceId) {
        Set<String> withOwnQris = userQrisRepository.findByBusinessId(businessId).stream()
                .map(UserQris::getUsername)
                .collect(Collectors.toSet());

        Set<String> recipients = new LinkedHashSet<>();
        // The super admin always gets it (oversight of every sale). Deliberately NOT removed when
        // they are the reporter: the exclusion below is per-device, so their other phones and
        // tablets still hear the sale.
        userRepository.findFirstByBusinessIdAndBusinessRoleAndDeletedAtIsNull(businessId, BusinessRole.SUPER_ADMIN)
                .map(User::getUsername)
                .ifPresent(recipients::add);

        // Everyone on the shared business QRIS — i.e. every member without one of their own.
        // A member WITH their own QRIS runs a separate merchant app and sees its own payments, so
        // they stay out. This no longer depends on whether the reporter has their own QRIS: who
        // detected the payment says nothing about who needs to hear about it.
        userRepository.findByBusinessIdAndBusinessRoleAndDeletedAtIsNullOrderByCreatedAtAsc(businessId, BusinessRole.MEMBER)
                .stream()
                .map(User::getUsername)
                .filter(u -> !withOwnQris.contains(u))
                .forEach(recipients::add);

        // The reporter's own account, whoever they are. "It already played the sound" is a fact
        // about one DEVICE, not an account: the phone that detected the payment announced it
        // before calling this API, but the merchant's other phones and tablets did not. Without
        // this line a member with their own QRIS was left out of their own sale entirely, since
        // they are in neither branch above. A LinkedHashSet, so this is a no-op when they are.
        // The reporting device itself is dropped per-device at the send below.
        recipients.add(creator);

        if (recipients.isEmpty()) {
            log.debug("[TXN] PARSED transactionId={} reported by {} — no one else to notify",
                    t.getTransactionId(), creator);
            return;
        }

        PushPayload payload = new TransactionPushPayload(t, merchantName(businessId),
                "Pembayaran Diterima", "Pembayaran " + Rupiah.format(t.getAmount()) + " diterima");
        List<String> targets = List.copyOf(recipients);
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            sendParsedPushQuietly(targets, payload, t.getTransactionId(), reportingDeviceId);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override public void afterCommit() {
                sendParsedPushQuietly(targets, payload, t.getTransactionId(), reportingDeviceId);
            }
        });
    }

    private void sendParsedPushQuietly(List<String> recipients, PushPayload payload, String transactionId,
                                       String excludeDeviceId) {
        try {
            int queued = pushNotificationService.sendToUsernames(recipients, payload, excludeDeviceId);
            log.info("[TXN] Notified {} account(s) of PARSED transactionId={}, excluding device {} ({} push job(s) queued)",
                    recipients.size(), transactionId, excludeDeviceId, queued);
        } catch (RuntimeException e) {
            log.warn("[TXN] Notification failed for PARSED transactionId={} (transaction still recorded): {}",
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

    /**
     * When a sale still waiting for payment will be cancelled automatically, or null if it isn't waiting.
     * The sweep cancels a sale once its day is over, at the first sweep after that: the midnight job, so
     * never earlier than the coming midnight. For a sale dated today that is the start of tomorrow; one
     * dated in the past is only picked up at the next midnight too; one dated ahead waits for its own day to end.
     */
    static LocalDateTime autoCancelAt(Transaction t) {
        if (!TransactionStatus.AWAITING_PAYMENT.equals(t.getStatus()) || t.getTransactionDate() == null) return null;
        LocalDateTime endOfItsDay = t.getTransactionDate().toLocalDate().plusDays(1).atStartOfDay();
        LocalDateTime nextMidnight = LocalDate.now(AppTime.ZONE).plusDays(1).atStartOfDay();
        return endOfItsDay.isAfter(nextMidnight) ? endOfItsDay : nextMidnight;
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
                t.getStatus(),
                t.getPaidAt(),
                autoCancelAt(t),
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
