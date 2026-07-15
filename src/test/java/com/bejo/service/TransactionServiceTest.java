package com.bejo.service;

import com.bejo.dto.request.AddTransactionRequestDto;
import com.bejo.dto.request.TransactionItemRequestDto;
import com.bejo.dto.request.UpdateTransactionRequestDto;
import com.bejo.dto.response.TransactionCreatedResponseDto;
import com.bejo.dto.response.TransactionResponseDto;
import com.bejo.entity.Business;
import com.bejo.entity.Product;
import com.bejo.entity.Transaction;
import com.bejo.exception.ResourceNotFoundException;
import com.bejo.repository.BusinessRepository;
import com.bejo.repository.ProductRepository;
import com.bejo.repository.TransactionItemRepository;
import com.bejo.repository.TransactionRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    private static final Long BIZ = 1L;

    @Mock private TransactionRepository transactionRepository;
    @Mock private TransactionItemRepository transactionItemRepository;
    @Mock private ProductRepository productRepository;
    @Mock private StockMutationService stockMutationService;
    @Mock private BusinessRepository businessRepository;
    @Mock private BusinessContext businessContext;

    private TransactionService transactionService;
    private static final jakarta.validation.Validator VALIDATOR =
            jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();

    @BeforeEach
    void setUp() {
        transactionService = new TransactionService(transactionRepository, transactionItemRepository,
                productRepository, stockMutationService, businessRepository, businessContext,
                VALIDATOR, new SimpleMeterRegistry());
        lenient().when(businessContext.requireBusinessId("alice")).thenReturn(BIZ);
    }

    private AddTransactionRequestDto request(String type, String acquirer) {
        return new AddTransactionRequestDto(type, LocalDateTime.now(), 50_000L,
                "GoPay", null, null, null, acquirer, null);
    }

    private AddTransactionRequestDto requestWithItems(List<TransactionItemRequestDto> items) {
        return new AddTransactionRequestDto("MANUAL", LocalDateTime.now(), 50_000L,
                "GoPay", null, null, null, null, items);
    }

    private Product product(long id, String name, int stock, long price) {
        Product p = new Product();
        p.setBusinessId(BIZ);
        p.setSku("SKU-" + id);
        p.setName(name);
        p.setStock(stock);
        p.setPrice(price);
        return p;
    }

    @Test
    void createWithoutIdempotencyKeyIsRejected() {
        assertThatThrownBy(() -> transactionService.create("alice", null, request("MANUAL", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Idempotency-Key");

        assertThatThrownBy(() -> transactionService.create("alice", "  ", request("MANUAL", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void idempotentReplayReturnsExistingTransaction() {
        Transaction existing = new Transaction();
        existing.setTransactionId("TXN-EXISTING");
        when(transactionRepository.findByIdempotencyKeyAndBusinessId("key-1", BIZ))
                .thenReturn(Optional.of(existing));

        TransactionCreatedResponseDto data = transactionService.create("alice", "key-1", request("MANUAL", null));

        assertThat(data.transactionId()).isEqualTo("TXN-EXISTING");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void parsedTypeRequiresAcquirer() {
        when(transactionRepository.findByIdempotencyKeyAndBusinessId("key-1", BIZ))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.create("alice", "key-1", request("PARSED", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pembayaran");
    }

    @Test
    void manualTypeDefaultsAcquirerToCash() {
        when(transactionRepository.findByIdempotencyKeyAndBusinessId("key-1", BIZ))
                .thenReturn(Optional.empty());

        transactionService.create("alice", "key-1", request("MANUAL", null));

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        Transaction saved = captor.getValue();
        assertThat(saved.getAcquirer()).isEqualTo("Cash");
        assertThat(saved.getType()).isEqualTo("MANUAL");
        assertThat(saved.getStatus()).isEqualTo("SUCCESS");
        assertThat(saved.getIdempotencyKey()).isEqualTo("key-1");
        assertThat(saved.getTransactionId()).startsWith("TXN-");
        assertThat(saved.getBusinessId()).isEqualTo(BIZ);
    }

    @Test
    void parsedTypeUsesProvidedAcquirer() {
        when(transactionRepository.findByIdempotencyKeyAndBusinessId("key-1", BIZ))
                .thenReturn(Optional.empty());

        transactionService.create("alice", "key-1", request("PARSED", "AstraPay"));

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getAcquirer()).isEqualTo("AstraPay");
    }

    @Test
    void createWithItemsReducesStockAndRecordsLineItemsAndSaleMutation() {
        when(transactionRepository.findByIdempotencyKeyAndBusinessId("key-1", BIZ))
                .thenReturn(Optional.empty());
        Product coffee = product(1, "Coffee", 10, 15_000L);
        Product tea = product(2, "Tea", 5, 8_000L);
        when(productRepository.findByIdAndBusinessId(1L, BIZ)).thenReturn(Optional.of(coffee));
        when(productRepository.findByIdAndBusinessId(2L, BIZ)).thenReturn(Optional.of(tea));

        transactionService.create("alice", "key-1", requestWithItems(List.of(
                new TransactionItemRequestDto(1L, 3),
                new TransactionItemRequestDto(2L, 2))));

        // Stock reduced by the sold quantities
        assertThat(coffee.getStock()).isEqualTo(7);
        assertThat(tea.getStock()).isEqualTo(3);
        verify(productRepository).save(coffee);
        verify(productRepository).save(tea);

        // One line item saved per product, priced from the product's selling price
        ArgumentCaptor<com.bejo.entity.TransactionItem> itemCaptor =
                ArgumentCaptor.forClass(com.bejo.entity.TransactionItem.class);
        verify(transactionItemRepository, times(2)).save(itemCaptor.capture());
        assertThat(itemCaptor.getAllValues())
                .anySatisfy(i -> {
                    assertThat(i.getName()).isEqualTo("Coffee");
                    assertThat(i.getQuantity()).isEqualTo(3);
                    assertThat(i.getUnitPrice()).isEqualTo(15_000L);
                    assertThat(i.getSubtotal()).isEqualTo(45_000L);
                });

        // A SALE stock mutation is recorded so the stock ledger stays complete
        verify(stockMutationService).recordStockChange(eq(BIZ), eq("SALE"), any(), any());

        // The amount is DERIVED from the line items (3*15000 + 2*8000 = 61000), overriding
        // the client-sent 50000
        ArgumentCaptor<Transaction> txnCaptor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, org.mockito.Mockito.atLeastOnce()).save(txnCaptor.capture());
        assertThat(txnCaptor.getValue().getAmount()).isEqualTo(61_000L);
    }

    @Test
    void createWithInsufficientStockIsRejectedAndNothingChanges() {
        when(transactionRepository.findByIdempotencyKeyAndBusinessId("key-1", BIZ))
                .thenReturn(Optional.empty());
        Product coffee = product(1, "Coffee", 2, 15_000L);
        when(productRepository.findByIdAndBusinessId(1L, BIZ)).thenReturn(Optional.of(coffee));

        assertThatThrownBy(() -> transactionService.create("alice", "key-1",
                requestWithItems(List.of(new TransactionItemRequestDto(1L, 5)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Stok tidak cukup");

        // Validation happens before any stock change / line-item write
        assertThat(coffee.getStock()).isEqualTo(2);
        verify(productRepository, never()).save(any());
        verify(transactionItemRepository, never()).save(any());
        verify(stockMutationService, never()).recordStockChange(any(), any(), any(), any());
    }

    @Test
    void createWithUnknownProductIsRejected() {
        when(transactionRepository.findByIdempotencyKeyAndBusinessId("key-1", BIZ))
                .thenReturn(Optional.empty());
        when(productRepository.findByIdAndBusinessId(99L, BIZ)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.create("alice", "key-1",
                requestWithItems(List.of(new TransactionItemRequestDto(99L, 1)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tidak ditemukan");
    }

    @Test
    void createRejectsDuplicateProductLines() {
        when(transactionRepository.findByIdempotencyKeyAndBusinessId("key-1", BIZ))
                .thenReturn(Optional.empty());
        when(productRepository.findByIdAndBusinessId(1L, BIZ))
                .thenReturn(Optional.of(product(1, "Coffee", 10, 15_000L)));

        assertThatThrownBy(() -> transactionService.create("alice", "key-1",
                requestWithItems(List.of(
                        new TransactionItemRequestDto(1L, 1),
                        new TransactionItemRequestDto(1L, 2)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("duplikat");
    }

    private static final Pageable PAGE = PageRequest.of(0, 50);

    @Test
    void historyWithoutDatesFetchesEverything() {
        when(transactionRepository.findByBusinessIdAndDeletedAtIsNullOrderByTransactionDateDesc(BIZ, PAGE))
                .thenReturn(Page.empty());

        Page<TransactionResponseDto> result = transactionService.getHistory("alice", null, null, PAGE);

        assertThat(result).isEmpty();
        verify(transactionRepository).findByBusinessIdAndDeletedAtIsNullOrderByTransactionDateDesc(BIZ, PAGE);
    }

    @Test
    void historyWithBothDatesUsesInclusiveEndOfDay() {
        when(transactionRepository.findByBusinessIdAndDeletedAtIsNullAndTransactionDateBetweenOrderByTransactionDateDesc(
                eq(BIZ), any(), any(), eq(PAGE))).thenReturn(Page.empty());

        transactionService.getHistory("alice", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 1), PAGE);

        ArgumentCaptor<LocalDateTime> endCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(transactionRepository).findByBusinessIdAndDeletedAtIsNullAndTransactionDateBetweenOrderByTransactionDateDesc(
                eq(BIZ), eq(LocalDate.of(2026, 7, 1).atStartOfDay()), endCaptor.capture(), eq(PAGE));
        // End bound must cover the entire final day
        assertThat(endCaptor.getValue()).isAfter(LocalDateTime.of(2026, 7, 1, 23, 59, 59));
        assertThat(endCaptor.getValue()).isBefore(LocalDateTime.of(2026, 7, 2, 0, 0));
    }

    @Test
    void historyMapsEntityToDtoWithMerchantName() {
        Business business = new Business();
        business.setName("Warung Alice");
        Transaction t = new Transaction();
        t.setTransactionId("TXN-1");
        t.setTransactionDate(LocalDateTime.now());
        t.setAmount(25_000L);
        t.setType("MANUAL");
        t.setAcquirer("Cash");
        t.setSource("GoPay");

        when(businessRepository.findById(BIZ)).thenReturn(Optional.of(business));
        when(transactionRepository.findByBusinessIdAndDeletedAtIsNullOrderByTransactionDateDesc(BIZ, PAGE))
                .thenReturn(new PageImpl<>(List.of(t)));

        Page<TransactionResponseDto> result = transactionService.getHistory("alice", null, null, PAGE);

        assertThat(result.getContent()).hasSize(1);
        assertThat(result.getContent().getFirst().merchantName()).isEqualTo("Warung Alice");
        assertThat(result.getContent().getFirst().issuerName()).isEqualTo("GoPay");
    }

    private Transaction activeTransaction() {
        Transaction t = new Transaction();
        t.setTransactionId("TXN-1");
        t.setTransactionDate(LocalDateTime.of(2026, 7, 1, 10, 0));
        t.setAmount(25_000L);
        t.setType("MANUAL");
        t.setAcquirer("Cash");
        t.setSource("GoPay");
        t.setBusinessId(BIZ);
        return t;
    }

    @Test
    void deleteSetsDeletedAtInsteadOfRemovingRow() {
        Transaction t = activeTransaction();
        when(transactionRepository.findByTransactionIdAndBusinessIdAndDeletedAtIsNull("TXN-1", BIZ))
                .thenReturn(Optional.of(t));

        transactionService.delete("alice", "TXN-1");

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getDeletedAt()).isNotNull();
        verify(transactionRepository, never()).delete(any());
    }

    @Test
    void deleteUnknownOrForeignTransactionIs404() {
        when(transactionRepository.findByTransactionIdAndBusinessIdAndDeletedAtIsNull("TXN-X", BIZ))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.delete("alice", "TXN-X"))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void updateAppliesOnlyProvidedFields() {
        Transaction t = activeTransaction();
        when(transactionRepository.findByTransactionIdAndBusinessIdAndDeletedAtIsNull("TXN-1", BIZ))
                .thenReturn(Optional.of(t));

        UpdateTransactionRequestDto req = new UpdateTransactionRequestDto(
                null, 99_000L, null, null, null, null, null, null);
        TransactionResponseDto dto = transactionService.update("alice", "TXN-1", req);

        assertThat(dto.amount()).isEqualTo(99_000L);
        // Untouched fields keep their values
        assertThat(dto.issuerName()).isEqualTo("GoPay");
        assertThat(dto.acquirer()).isEqualTo("Cash");
        assertThat(dto.transactionDate()).isEqualTo(LocalDateTime.of(2026, 7, 1, 10, 0));
        verify(transactionRepository).save(t);
    }

    @Test
    void updateWithNoFieldsIsRejected() {
        UpdateTransactionRequestDto empty = new UpdateTransactionRequestDto(
                null, null, null, null, null, null, null, null);

        assertThatThrownBy(() -> transactionService.update("alice", "TXN-1", empty))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tidak ada data");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void updateManagedTransactionIsRejected() {
        Transaction t = activeTransaction();
        t.setType("MANAGED");
        t.setAcquirer("AstraPay");
        when(transactionRepository.findByTransactionIdAndBusinessIdAndDeletedAtIsNull("TXN-1", BIZ))
                .thenReturn(Optional.of(t));

        UpdateTransactionRequestDto req = new UpdateTransactionRequestDto(
                null, 99_000L, null, null, null, null, null, null);

        assertThatThrownBy(() -> transactionService.update("alice", "TXN-1", req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tidak dapat diubah")
                .hasMessageContaining("tidak dapat diubah");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void updateWithBlankAcquirerIsRejected() {
        Transaction t = activeTransaction();
        when(transactionRepository.findByTransactionIdAndBusinessIdAndDeletedAtIsNull("TXN-1", BIZ))
                .thenReturn(Optional.of(t));

        UpdateTransactionRequestDto req = new UpdateTransactionRequestDto(
                null, null, null, null, null, null, "  ", null);

        assertThatThrownBy(() -> transactionService.update("alice", "TXN-1", req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("pembayaran");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void updateUnknownOrDeletedTransactionIs404() {
        when(transactionRepository.findByTransactionIdAndBusinessIdAndDeletedAtIsNull("TXN-X", BIZ))
                .thenReturn(Optional.empty());

        UpdateTransactionRequestDto req = new UpdateTransactionRequestDto(
                null, 10_000L, null, null, null, null, null, null);

        assertThatThrownBy(() -> transactionService.update("alice", "TXN-X", req))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    // ---------- editing transaction items ----------

    private static void setId(Object entity, long id) {
        try {
            var f = entity.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) { throw new RuntimeException(e); }
    }

    private com.bejo.entity.TransactionItem existingItem(long historyId, long productId, String name, int qty) {
        var it = new com.bejo.entity.TransactionItem();
        it.setHistoryId(historyId);
        it.setProductId(productId);
        it.setSku("SKU-" + productId);
        it.setName(name);
        it.setQuantity(qty);
        it.setUnitPrice(0);
        it.setSubtotal(0);
        return it;
    }

    private UpdateTransactionRequestDto itemsUpdate(List<TransactionItemRequestDto> items) {
        return new UpdateTransactionRequestDto(null, null, null, null, null, null, null, items);
    }

    @Test
    void updateIncreasingItemQuantityDrawsMoreStockAndRederivesAmount() {
        Transaction t = activeTransaction();
        setId(t, 42L);
        when(transactionRepository.findByTransactionIdAndBusinessIdAndDeletedAtIsNull("TXN-1", BIZ))
                .thenReturn(Optional.of(t));
        Product coffee = product(1, "Coffee", 5, 15_000L);   // 2 sold earlier, 5 on hand
        var existing = existingItem(42L, 1L, "Coffee", 2);
        // reconcile sees the existing item; the response query then sees the (mock) empty set
        when(transactionItemRepository.findByHistoryIdOrderByIdAsc(42L))
                .thenReturn(List.of(existing), List.of());
        when(productRepository.findByIdAndBusinessId(1L, BIZ)).thenReturn(Optional.of(coffee));

        transactionService.update("alice", "TXN-1", itemsUpdate(List.of(new TransactionItemRequestDto(1L, 5))));

        // 2 → 5 = 3 more sold, stock 5 → 2
        assertThat(coffee.getStock()).isEqualTo(2);
        verify(productRepository).save(coffee);
        verify(transactionItemRepository).deleteAll(List.of(existing));
        // amount re-derived: 5 * 15000
        ArgumentCaptor<Transaction> cap = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(cap.capture());
        assertThat(cap.getValue().getAmount()).isEqualTo(75_000L);
        verify(stockMutationService).recordStockChange(eq(BIZ), eq("SALE"), any(), any());
    }

    @Test
    void updateWithEmptyItemsRestoresStockAndClearsItems() {
        Transaction t = activeTransaction();
        setId(t, 42L);
        when(transactionRepository.findByTransactionIdAndBusinessIdAndDeletedAtIsNull("TXN-1", BIZ))
                .thenReturn(Optional.of(t));
        Product coffee = product(1, "Coffee", 2, 15_000L);   // 3 sold earlier, 2 on hand
        var existing = existingItem(42L, 1L, "Coffee", 3);
        when(transactionItemRepository.findByHistoryIdOrderByIdAsc(42L))
                .thenReturn(List.of(existing), List.of());
        when(productRepository.findByIdAndBusinessId(1L, BIZ)).thenReturn(Optional.of(coffee));

        transactionService.update("alice", "TXN-1", itemsUpdate(List.of()));

        // all 3 returned, stock 2 → 5
        assertThat(coffee.getStock()).isEqualTo(5);
        verify(transactionItemRepository).deleteAll(List.of(existing));
        verify(transactionItemRepository, never()).save(any());   // no new item rows
        verify(stockMutationService).recordStockChange(eq(BIZ), eq("SALE"), any(), any());
    }

    @Test
    void updateItemsRejectedWhenIncreaseExceedsStockAndNothingChanges() {
        Transaction t = activeTransaction();
        setId(t, 42L);
        when(transactionRepository.findByTransactionIdAndBusinessIdAndDeletedAtIsNull("TXN-1", BIZ))
                .thenReturn(Optional.of(t));
        Product coffee = product(1, "Coffee", 1, 15_000L);   // only 1 on hand
        var existing = existingItem(42L, 1L, "Coffee", 2);
        when(transactionItemRepository.findByHistoryIdOrderByIdAsc(42L)).thenReturn(List.of(existing));
        when(productRepository.findByIdAndBusinessId(1L, BIZ)).thenReturn(Optional.of(coffee));

        // want 4 → +2 needed, only 1 available
        assertThatThrownBy(() -> transactionService.update("alice", "TXN-1",
                itemsUpdate(List.of(new TransactionItemRequestDto(1L, 4)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Stok tidak cukup");

        assertThat(coffee.getStock()).isEqualTo(1);              // unchanged
        verify(productRepository, never()).save(any());
        verify(transactionItemRepository, never()).deleteAll(anyList());
        verify(stockMutationService, never()).recordStockChange(any(), any(), any(), any());
    }

    @Test
    void deleteReturnsStockForLineItems() {
        Transaction t = activeTransaction();
        setId(t, 42L);
        when(transactionRepository.findByTransactionIdAndBusinessIdAndDeletedAtIsNull("TXN-1", BIZ))
                .thenReturn(Optional.of(t));
        Product coffee = product(1, "Coffee", 7, 15_000L);   // 3 sold earlier, 7 on hand
        var existing = existingItem(42L, 1L, "Coffee", 3);
        when(transactionItemRepository.findByHistoryIdOrderByIdAsc(42L)).thenReturn(List.of(existing));
        when(productRepository.findByIdAndBusinessId(1L, BIZ)).thenReturn(Optional.of(coffee));

        transactionService.delete("alice", "TXN-1");

        assertThat(coffee.getStock()).isEqualTo(10);   // 3 returned
        verify(productRepository).save(coffee);
        verify(stockMutationService).recordStockChange(eq(BIZ), eq("SALE"), any(), any());
        assertThat(t.getDeletedAt()).isNotNull();      // still soft-deleted
    }

    // ---------- bulk create ----------

    private com.bejo.dto.request.BulkTransactionItemDto bulkItem(String key, AddTransactionRequestDto tx) {
        return new com.bejo.dto.request.BulkTransactionItemDto(key, tx);
    }

    @Test
    void bulkCreateSavesAllValidItems() {
        when(transactionRepository.findByIdempotencyKeyAndBusinessId(any(), eq(BIZ)))
                .thenReturn(Optional.empty());

        var request = new com.bejo.dto.request.BulkAddTransactionRequestDto(List.of(
                bulkItem("k1", request("MANUAL", null)),
                bulkItem("k2", request("PARSED", "AstraPay"))));

        var result = transactionService.bulkCreate("alice", request);

        assertThat(result.total()).isEqualTo(2);
        assertThat(result.created()).isEqualTo(2);
        assertThat(result.duplicate()).isZero();
        assertThat(result.failed()).isZero();
        assertThat(result.results()).allMatch(r -> "CREATED".equals(r.status()) && r.transactionId() != null);
        verify(transactionRepository, times(2)).save(any());
    }

    @Test
    void bulkCreateMarksExistingKeyAsDuplicate() {
        Transaction existing = new Transaction();
        existing.setTransactionId("TXN-EXISTING");
        when(transactionRepository.findByIdempotencyKeyAndBusinessId("k1", BIZ))
                .thenReturn(Optional.of(existing));
        when(transactionRepository.findByIdempotencyKeyAndBusinessId("k2", BIZ))
                .thenReturn(Optional.empty());

        var request = new com.bejo.dto.request.BulkAddTransactionRequestDto(List.of(
                bulkItem("k1", request("MANUAL", null)),
                bulkItem("k2", request("MANUAL", null))));

        var result = transactionService.bulkCreate("alice", request);

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.duplicate()).isEqualTo(1);
        var dup = result.results().stream().filter(r -> r.idempotencyKey().equals("k1")).findFirst().orElseThrow();
        assertThat(dup.status()).isEqualTo("DUPLICATE");
        assertThat(dup.transactionId()).isEqualTo("TXN-EXISTING");
        verify(transactionRepository, times(1)).save(any());
    }

    @Test
    void bulkCreateFailsInvalidItemButKeepsGoodOnes() {
        when(transactionRepository.findByIdempotencyKeyAndBusinessId(any(), eq(BIZ)))
                .thenReturn(Optional.empty());

        // Second item is invalid: amount must be positive
        AddTransactionRequestDto bad = new AddTransactionRequestDto("MANUAL", LocalDateTime.now(), -5L,
                null, null, null, null, null, null);

        var request = new com.bejo.dto.request.BulkAddTransactionRequestDto(List.of(
                bulkItem("k1", request("MANUAL", null)),
                bulkItem("k2", bad)));

        var result = transactionService.bulkCreate("alice", request);

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.failed()).isEqualTo(1);
        var fail = result.results().stream().filter(r -> r.idempotencyKey().equals("k2")).findFirst().orElseThrow();
        assertThat(fail.status()).isEqualTo("FAILED");
        assertThat(fail.error()).contains("amount");
        // Only the valid item is saved
        verify(transactionRepository, times(1)).save(any());
    }

    @Test
    void bulkCreateFailsParsedWithoutAcquirer() {
        when(transactionRepository.findByIdempotencyKeyAndBusinessId(any(), eq(BIZ)))
                .thenReturn(Optional.empty());

        var request = new com.bejo.dto.request.BulkAddTransactionRequestDto(List.of(
                bulkItem("k1", request("PARSED", null))));

        var result = transactionService.bulkCreate("alice", request);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.results().getFirst().error()).contains("pembayaran");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void bulkCreateBlankIdempotencyKeyFailsThatItem() {
        var request = new com.bejo.dto.request.BulkAddTransactionRequestDto(List.of(
                bulkItem("  ", request("MANUAL", null))));

        var result = transactionService.bulkCreate("alice", request);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.results().getFirst().error()).contains("idempotencyKey");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void bulkCreateUnknownUserIs404() {
        when(businessContext.requireBusinessId("ghost"))
                .thenThrow(new ResourceNotFoundException("User not found"));

        var request = new com.bejo.dto.request.BulkAddTransactionRequestDto(List.of(
                bulkItem("k1", request("MANUAL", null))));

        assertThatThrownBy(() -> transactionService.bulkCreate("ghost", request))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
