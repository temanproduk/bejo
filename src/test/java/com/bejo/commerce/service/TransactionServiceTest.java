package com.bejo.commerce.service;
import com.bejo.core.service.BusinessContext;

import com.bejo.commerce.dto.AddTransactionRequestDto;
import com.bejo.commerce.dto.TransactionItemRequestDto;
import com.bejo.commerce.dto.UpdateTransactionRequestDto;
import com.bejo.commerce.dto.TransactionCreatedResponseDto;
import com.bejo.commerce.dto.TransactionResponseDto;
import com.bejo.core.domain.Business;
import com.bejo.core.domain.BusinessRole;
import com.bejo.core.domain.User;
import com.bejo.core.domain.UserQris;
import com.bejo.commerce.domain.Product;
import com.bejo.commerce.domain.Transaction;
import com.bejo.shared.exception.ResourceNotFoundException;
import com.bejo.core.repository.BusinessRepository;
import com.bejo.commerce.repository.ProductRepository;
import com.bejo.commerce.repository.TransactionItemRepository;
import com.bejo.commerce.repository.TransactionRepository;
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
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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

    // Products created by product(...), keyed by id — backs the atomic-stock fake below.
    private final java.util.Map<Long, Product> productsById = new java.util.HashMap<>();

    @Mock private TransactionRepository transactionRepository;
    @Mock private TransactionItemRepository transactionItemRepository;
    @Mock private ProductRepository productRepository;
    @Mock private StockMutationService stockMutationService;
    @Mock private BusinessRepository businessRepository;
    @Mock private com.bejo.core.repository.UserRepository userRepository;
    @Mock private com.bejo.core.repository.UserQrisRepository userQrisRepository;
    @Mock private com.bejo.notification.PushNotificationService pushNotificationService;
    @Mock private BusinessContext businessContext;

    private TransactionService transactionService;
    private static final jakarta.validation.Validator VALIDATOR =
            jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();

    @BeforeEach
    void setUp() {
        transactionService = new TransactionService(transactionRepository, transactionItemRepository,
                productRepository, stockMutationService, businessRepository, userRepository,
                userQrisRepository, pushNotificationService, businessContext,
                VALIDATOR, new SimpleMeterRegistry());
        stubAtomicStock();
        lenient().when(businessContext.requireBusinessId("alice")).thenReturn(BIZ);
        lenient().when(businessContext.requireSuperAdminBusinessId("alice")).thenReturn(BIZ);  // alice = super admin (delete)
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
        org.springframework.test.util.ReflectionTestUtils.setField(p, "id", id);
        p.setBusinessId(BIZ);
        p.setSku("SKU-" + id);
        p.setName(name);
        p.setStock(stock);
        p.setPrice(price);
        productsById.put(id, p);
        return p;
    }

    /**
     * Stands in for the database's atomic stock arithmetic. The service no longer does
     * read-modify-write on the entity (that lost concurrent updates) — it issues conditional
     * UPDATEs — so these fakes apply the same arithmetic to the in-memory products, letting the
     * tests keep asserting on the resulting stock and on "not enough stock" being refused.
     */
    private void stubAtomicStock() {
        lenient().when(productRepository.decreaseStock(any(), eq(BIZ), anyInt())).thenAnswer(inv -> {
            Product p = productsById.get(inv.<Long>getArgument(0));
            int qty = inv.getArgument(2);
            if (p == null || p.getStock() < qty) return 0;
            p.setStock(p.getStock() - qty);
            return 1;
        });
        lenient().when(productRepository.increaseStock(any(), eq(BIZ), anyInt())).thenAnswer(inv -> {
            Product p = productsById.get(inv.<Long>getArgument(0));
            if (p == null) return 0;
            p.setStock(p.getStock() + inv.<Integer>getArgument(2));
            return 1;
        });
        lenient().when(productRepository.findStockByIdIn(any())).thenAnswer(inv -> {
            java.util.Collection<Long> ids = inv.getArgument(0);
            return ids.stream().filter(productsById::containsKey).map(id -> {
                Product p = productsById.get(id);
                return (com.bejo.commerce.repository.ProductStock) new com.bejo.commerce.repository.ProductStock() {
                    @Override public Long getId() { return id; }
                    @Override public int getStock() { return p.getStock(); }
                };
            }).toList();
        });
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
    void createSnapshotsCreatedByAuthor() {
        when(transactionRepository.findByIdempotencyKeyAndBusinessId("key-1", BIZ))
                .thenReturn(Optional.empty());

        transactionService.create("alice", "key-1", request("MANUAL", null));

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getCreatedBy()).isEqualTo("alice");
        assertThat(captor.getValue().getUpdatedBy()).isNull();   // never edited yet
    }

    // PARSED detected by the super admin (payment on the shared business QRIS):
    // notify the super admin (included — its app already played + dedupes) + all members on the
    // shared QRIS; a member with their own QRIS runs a separate app and is skipped.
    @Test
    @SuppressWarnings("unchecked")
    void parsedByAdminNotifiesAdminAndSharedQrisMembers() {
        when(transactionRepository.findByIdempotencyKeyAndBusinessId("key-1", BIZ))
                .thenReturn(Optional.empty());
        when(userRepository.findFirstByBusinessIdAndBusinessRoleAndDeletedAtIsNull(BIZ, BusinessRole.SUPER_ADMIN))
                .thenReturn(Optional.of(named("alice")));
        UserQris ownQris = new UserQris();
        ownQris.setUsername("emp_with_qris");
        when(userQrisRepository.findByBusinessId(BIZ)).thenReturn(List.of(ownQris));
        when(userRepository.findByBusinessIdAndBusinessRoleAndDeletedAtIsNullOrderByCreatedAtAsc(BIZ, BusinessRole.MEMBER))
                .thenReturn(List.of(member("emp_with_qris"), member("emp_shared")));

        transactionService.create("alice", "key-1", request("PARSED", "AstraPay"));   // detected by the super admin

        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(pushNotificationService).sendToUsernames(captor.capture(), any());
        assertThat(captor.getValue()).containsExactlyInAnyOrder("alice", "emp_shared");
    }

    // PARSED detected by a sub-account with its own QRIS (payment on that sub's separate QRIS):
    // only the super admin (oversight) + that sub-account; no shared-QRIS members.
    @Test
    @SuppressWarnings("unchecked")
    void parsedByOwnQrisMemberNotifiesAdminAndThatMemberOnly() {
        lenient().when(businessContext.requireBusinessId("emp_own")).thenReturn(BIZ);
        when(transactionRepository.findByIdempotencyKeyAndBusinessId("key-1", BIZ))
                .thenReturn(Optional.empty());
        when(userRepository.findFirstByBusinessIdAndBusinessRoleAndDeletedAtIsNull(BIZ, BusinessRole.SUPER_ADMIN))
                .thenReturn(Optional.of(named("alice")));
        UserQris ownQris = new UserQris();
        ownQris.setUsername("emp_own");
        when(userQrisRepository.findByBusinessId(BIZ)).thenReturn(List.of(ownQris));

        transactionService.create("emp_own", "key-1", request("PARSED", "AstraPay"));   // detected by the own-QRIS sub

        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(pushNotificationService).sendToUsernames(captor.capture(), any());
        assertThat(captor.getValue()).containsExactlyInAnyOrder("alice", "emp_own");
    }

    // Shared-QRIS cohort empty (every member has their own QRIS) → the admin alone is notified.
    @Test
    @SuppressWarnings("unchecked")
    void parsedByAdminWithNoSharedQrisMembersNotifiesAdminOnly() {
        when(transactionRepository.findByIdempotencyKeyAndBusinessId("key-1", BIZ))
                .thenReturn(Optional.empty());
        when(userRepository.findFirstByBusinessIdAndBusinessRoleAndDeletedAtIsNull(BIZ, BusinessRole.SUPER_ADMIN))
                .thenReturn(Optional.of(named("alice")));
        UserQris ownQris = new UserQris();
        ownQris.setUsername("emp_solo");
        when(userQrisRepository.findByBusinessId(BIZ)).thenReturn(List.of(ownQris));
        when(userRepository.findByBusinessIdAndBusinessRoleAndDeletedAtIsNullOrderByCreatedAtAsc(BIZ, BusinessRole.MEMBER))
                .thenReturn(List.of(member("emp_solo")));

        transactionService.create("alice", "key-1", request("PARSED", "AstraPay"));

        ArgumentCaptor<Collection<String>> captor = ArgumentCaptor.forClass(Collection.class);
        verify(pushNotificationService).sendToUsernames(captor.capture(), any());
        assertThat(captor.getValue()).containsExactly("alice");
    }

    // The app renders the push itself (data-only), so the body must arrive already formatted
    // as Indonesian currency.
    @Test
    void parsedPushBodyFormatsTheAmountAsRupiah() {
        when(transactionRepository.findByIdempotencyKeyAndBusinessId("key-1", BIZ))
                .thenReturn(Optional.empty());
        when(userRepository.findFirstByBusinessIdAndBusinessRoleAndDeletedAtIsNull(BIZ, BusinessRole.SUPER_ADMIN))
                .thenReturn(Optional.of(named("alice")));
        when(userQrisRepository.findByBusinessId(BIZ)).thenReturn(List.of());

        transactionService.create("alice", "key-1", request("PARSED", "AstraPay"));   // amount 50_000

        ArgumentCaptor<com.bejo.notification.PushPayload> captor =
                ArgumentCaptor.forClass(com.bejo.notification.PushPayload.class);
        verify(pushNotificationService).sendToUsernames(any(), captor.capture());
        var data = captor.getValue().toData();

        assertThat(data.get("body")).isEqualTo("Pembayaran Rp 50.000 diterima");
        assertThat(data.get("title")).isEqualTo("Pembayaran Diterima");
        // Everything the app needs rides in the data map; urgent keeps Android priority HIGH so a
        // backgrounded app still wakes and announces the payment.
        assertThat(data).containsKeys("type", "title", "body", "transactionId", "amount");
        assertThat(captor.getValue().urgent()).isTrue();
    }

    private User member(String username) {
        User u = new User();
        u.setUsername(username);
        u.setBusinessRole(BusinessRole.MEMBER);
        return u;
    }

    // A user row where only the username matters (e.g. the super-admin lookup result).
    private User named(String username) {
        User u = new User();
        u.setUsername(username);
        return u;
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
        verify(productRepository).decreaseStock(1L, BIZ, 3);
        verify(productRepository).decreaseStock(2L, BIZ, 2);

        // One line item saved per product, priced from the product's selling price
        ArgumentCaptor<com.bejo.commerce.domain.TransactionItem> itemCaptor =
                ArgumentCaptor.forClass(com.bejo.commerce.domain.TransactionItem.class);
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
        verify(productRepository, never()).decreaseStock(any(), any(), anyInt());
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
    void deleteByNonSuperAdminIsForbidden() {
        when(businessContext.requireSuperAdminBusinessId("bob"))
                .thenThrow(new com.bejo.shared.exception.ForbiddenException("Hanya super admin yang dapat mengubah pengaturan bisnis"));

        assertThatThrownBy(() -> transactionService.delete("bob", "TXN-1"))
                .isInstanceOf(com.bejo.shared.exception.ForbiddenException.class);
        verify(transactionRepository, never()).save(any());
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

    private com.bejo.commerce.domain.TransactionItem existingItem(long historyId, long productId, String name, int qty) {
        var it = new com.bejo.commerce.domain.TransactionItem();
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
        verify(productRepository, never()).decreaseStock(any(), any(), anyInt());
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
        verify(stockMutationService).recordStockChange(eq(BIZ), eq("SALE"), any(), any());
        assertThat(t.getDeletedAt()).isNotNull();      // still soft-deleted
    }

    // ---------- bulk create ----------

    private com.bejo.commerce.dto.BulkTransactionItemDto bulkItem(String key, AddTransactionRequestDto tx) {
        return new com.bejo.commerce.dto.BulkTransactionItemDto(key, tx);
    }

    @Test
    void bulkCreateSavesAllValidItems() {
        when(transactionRepository.findByIdempotencyKeyAndBusinessId(any(), eq(BIZ)))
                .thenReturn(Optional.empty());

        var request = new com.bejo.commerce.dto.BulkAddTransactionRequestDto(List.of(
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

        var request = new com.bejo.commerce.dto.BulkAddTransactionRequestDto(List.of(
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

        var request = new com.bejo.commerce.dto.BulkAddTransactionRequestDto(List.of(
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

        var request = new com.bejo.commerce.dto.BulkAddTransactionRequestDto(List.of(
                bulkItem("k1", request("PARSED", null))));

        var result = transactionService.bulkCreate("alice", request);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.results().getFirst().error()).contains("pembayaran");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void bulkCreateBlankIdempotencyKeyFailsThatItem() {
        var request = new com.bejo.commerce.dto.BulkAddTransactionRequestDto(List.of(
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

        var request = new com.bejo.commerce.dto.BulkAddTransactionRequestDto(List.of(
                bulkItem("k1", request("MANUAL", null))));

        assertThatThrownBy(() -> transactionService.bulkCreate("ghost", request))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
