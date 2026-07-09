package com.bejo.service;

import com.bejo.dto.request.AddTransactionRequestDto;
import com.bejo.dto.request.UpdateTransactionRequestDto;
import com.bejo.dto.response.TransactionCreatedResponseDto;
import com.bejo.dto.response.TransactionResponseDto;
import com.bejo.entity.Transaction;
import com.bejo.entity.User;
import com.bejo.repository.TransactionRepository;
import com.bejo.repository.UserRepository;
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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private UserRepository userRepository;

    private TransactionService transactionService;
    private static final jakarta.validation.Validator VALIDATOR =
            jakarta.validation.Validation.buildDefaultValidatorFactory().getValidator();

    @BeforeEach
    void setUp() {
        transactionService = new TransactionService(transactionRepository, userRepository, VALIDATOR, new SimpleMeterRegistry());
    }

    private AddTransactionRequestDto request(String type, String acquirer) {
        return new AddTransactionRequestDto(type, LocalDateTime.now(), 50_000L,
                "GoPay", null, null, null, acquirer);
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
        when(transactionRepository.findByIdempotencyKeyAndUsername("key-1", "alice"))
                .thenReturn(Optional.of(existing));

        TransactionCreatedResponseDto data = transactionService.create("alice", "key-1", request("MANUAL", null));

        assertThat(data.transactionId()).isEqualTo("TXN-EXISTING");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void parsedTypeRequiresAcquirer() {
        when(transactionRepository.findByIdempotencyKeyAndUsername("key-1", "alice"))
                .thenReturn(Optional.empty());
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(new User()));

        assertThatThrownBy(() -> transactionService.create("alice", "key-1", request("PARSED", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("acquirer");
    }

    @Test
    void manualTypeDefaultsAcquirerToCash() {
        when(transactionRepository.findByIdempotencyKeyAndUsername("key-1", "alice"))
                .thenReturn(Optional.empty());
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(new User()));

        transactionService.create("alice", "key-1", request("MANUAL", null));

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        Transaction saved = captor.getValue();
        assertThat(saved.getAcquirer()).isEqualTo("Cash");
        assertThat(saved.getType()).isEqualTo("MANUAL");
        assertThat(saved.getStatus()).isEqualTo("SUCCESS");
        assertThat(saved.getIdempotencyKey()).isEqualTo("key-1");
        assertThat(saved.getTransactionId()).startsWith("TXN-");
    }

    @Test
    void parsedTypeUsesProvidedAcquirer() {
        when(transactionRepository.findByIdempotencyKeyAndUsername("key-1", "alice"))
                .thenReturn(Optional.empty());
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(new User()));

        transactionService.create("alice", "key-1", request("PARSED", "AstraPay"));

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getAcquirer()).isEqualTo("AstraPay");
    }

    private static final Pageable PAGE = PageRequest.of(0, 50);

    @Test
    void historyWithoutDatesFetchesEverything() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(new User()));
        when(transactionRepository.findByUsernameAndDeletedAtIsNullOrderByTransactionDateDesc("alice", PAGE))
                .thenReturn(Page.empty());

        Page<TransactionResponseDto> result = transactionService.getHistory("alice", null, null, PAGE);

        assertThat(result).isEmpty();
        verify(transactionRepository).findByUsernameAndDeletedAtIsNullOrderByTransactionDateDesc("alice", PAGE);
    }

    @Test
    void historyWithBothDatesUsesInclusiveEndOfDay() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(new User()));
        when(transactionRepository.findByUsernameAndDeletedAtIsNullAndTransactionDateBetweenOrderByTransactionDateDesc(
                eq("alice"), any(), any(), eq(PAGE))).thenReturn(Page.empty());

        transactionService.getHistory("alice", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 1), PAGE);

        ArgumentCaptor<LocalDateTime> endCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(transactionRepository).findByUsernameAndDeletedAtIsNullAndTransactionDateBetweenOrderByTransactionDateDesc(
                eq("alice"), eq(LocalDate.of(2026, 7, 1).atStartOfDay()), endCaptor.capture(), eq(PAGE));
        // End bound must cover the entire final day
        assertThat(endCaptor.getValue()).isAfter(LocalDateTime.of(2026, 7, 1, 23, 59, 59));
        assertThat(endCaptor.getValue()).isBefore(LocalDateTime.of(2026, 7, 2, 0, 0));
    }

    @Test
    void historyMapsEntityToDtoWithMerchantName() {
        User u = new User();
        u.setBusinessName("Warung Alice");
        Transaction t = new Transaction();
        t.setTransactionId("TXN-1");
        t.setTransactionDate(LocalDateTime.now());
        t.setAmount(25_000L);
        t.setType("MANUAL");
        t.setAcquirer("Cash");
        t.setSource("GoPay");

        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(u));
        when(transactionRepository.findByUsernameAndDeletedAtIsNullOrderByTransactionDateDesc("alice", PAGE))
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
        t.setUsername("alice");
        return t;
    }

    @Test
    void deleteSetsDeletedAtInsteadOfRemovingRow() {
        Transaction t = activeTransaction();
        when(transactionRepository.findByTransactionIdAndUsernameAndDeletedAtIsNull("TXN-1", "alice"))
                .thenReturn(Optional.of(t));

        transactionService.delete("alice", "TXN-1");

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getDeletedAt()).isNotNull();
        verify(transactionRepository, never()).delete(any());
    }

    @Test
    void deleteUnknownOrForeignTransactionIs404() {
        when(transactionRepository.findByTransactionIdAndUsernameAndDeletedAtIsNull("TXN-X", "alice"))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> transactionService.delete("alice", "TXN-X"))
                .isInstanceOf(com.bejo.exception.ResourceNotFoundException.class);
    }

    @Test
    void updateAppliesOnlyProvidedFields() {
        Transaction t = activeTransaction();
        when(transactionRepository.findByTransactionIdAndUsernameAndDeletedAtIsNull("TXN-1", "alice"))
                .thenReturn(Optional.of(t));
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(new User()));

        UpdateTransactionRequestDto req = new UpdateTransactionRequestDto(
                null, 99_000L, null, null, null, null, null);
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
                null, null, null, null, null, null, null);

        assertThatThrownBy(() -> transactionService.update("alice", "TXN-1", empty))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tidak ada field");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void updateManagedTransactionIsRejected() {
        Transaction t = activeTransaction();
        t.setType("MANAGED");
        t.setAcquirer("AstraPay");
        when(transactionRepository.findByTransactionIdAndUsernameAndDeletedAtIsNull("TXN-1", "alice"))
                .thenReturn(Optional.of(t));

        UpdateTransactionRequestDto req = new UpdateTransactionRequestDto(
                null, 99_000L, null, null, null, null, null);

        assertThatThrownBy(() -> transactionService.update("alice", "TXN-1", req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MANAGED")
                .hasMessageContaining("tidak dapat diubah");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void updateWithBlankAcquirerIsRejected() {
        Transaction t = activeTransaction();
        when(transactionRepository.findByTransactionIdAndUsernameAndDeletedAtIsNull("TXN-1", "alice"))
                .thenReturn(Optional.of(t));

        UpdateTransactionRequestDto req = new UpdateTransactionRequestDto(
                null, null, null, null, null, null, "  ");

        assertThatThrownBy(() -> transactionService.update("alice", "TXN-1", req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("acquirer");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void updateUnknownOrDeletedTransactionIs404() {
        when(transactionRepository.findByTransactionIdAndUsernameAndDeletedAtIsNull("TXN-X", "alice"))
                .thenReturn(Optional.empty());

        UpdateTransactionRequestDto req = new UpdateTransactionRequestDto(
                null, 10_000L, null, null, null, null, null);

        assertThatThrownBy(() -> transactionService.update("alice", "TXN-X", req))
                .isInstanceOf(com.bejo.exception.ResourceNotFoundException.class);
    }

    // ---------- bulk create ----------

    private com.bejo.dto.request.BulkTransactionItemDto bulkItem(String key, AddTransactionRequestDto tx) {
        return new com.bejo.dto.request.BulkTransactionItemDto(key, tx);
    }

    @Test
    void bulkCreateSavesAllValidItems() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(new User()));
        when(transactionRepository.findByIdempotencyKeyAndUsername(any(), eq("alice")))
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
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(new User()));
        Transaction existing = new Transaction();
        existing.setTransactionId("TXN-EXISTING");
        when(transactionRepository.findByIdempotencyKeyAndUsername("k1", "alice"))
                .thenReturn(Optional.of(existing));
        when(transactionRepository.findByIdempotencyKeyAndUsername("k2", "alice"))
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
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(new User()));
        when(transactionRepository.findByIdempotencyKeyAndUsername(any(), eq("alice")))
                .thenReturn(Optional.empty());

        // Second item is invalid: amount must be positive
        AddTransactionRequestDto bad = new AddTransactionRequestDto("MANUAL", LocalDateTime.now(), -5L,
                null, null, null, null, null);

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
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(new User()));
        when(transactionRepository.findByIdempotencyKeyAndUsername(any(), eq("alice")))
                .thenReturn(Optional.empty());

        var request = new com.bejo.dto.request.BulkAddTransactionRequestDto(List.of(
                bulkItem("k1", request("PARSED", null))));

        var result = transactionService.bulkCreate("alice", request);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.results().getFirst().error()).contains("acquirer");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void bulkCreateBlankIdempotencyKeyFailsThatItem() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(new User()));

        var request = new com.bejo.dto.request.BulkAddTransactionRequestDto(List.of(
                bulkItem("  ", request("MANUAL", null))));

        var result = transactionService.bulkCreate("alice", request);

        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.results().getFirst().error()).contains("idempotencyKey");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void bulkCreateUnknownUserIs404() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        var request = new com.bejo.dto.request.BulkAddTransactionRequestDto(List.of(
                bulkItem("k1", request("MANUAL", null))));

        assertThatThrownBy(() -> transactionService.bulkCreate("ghost", request))
                .isInstanceOf(com.bejo.exception.ResourceNotFoundException.class);
    }
}
