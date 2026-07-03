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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionServiceTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private UserRepository userRepository;

    private TransactionService transactionService;

    @BeforeEach
    void setUp() {
        transactionService = new TransactionService(transactionRepository, userRepository, new SimpleMeterRegistry());
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

    @Test
    void historyWithoutDatesFetchesEverything() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(new User()));
        when(transactionRepository.findByUsernameAndDeletedAtIsNullOrderByTransactionDateDesc("alice"))
                .thenReturn(List.of());

        List<TransactionResponseDto> result = transactionService.getHistory("alice", null, null);

        assertThat(result).isEmpty();
        verify(transactionRepository).findByUsernameAndDeletedAtIsNullOrderByTransactionDateDesc("alice");
    }

    @Test
    void historyWithBothDatesUsesInclusiveEndOfDay() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(new User()));
        when(transactionRepository.findByUsernameAndDeletedAtIsNullAndTransactionDateBetweenOrderByTransactionDateDesc(
                eq("alice"), any(), any())).thenReturn(List.of());

        transactionService.getHistory("alice", LocalDate.of(2026, 7, 1), LocalDate.of(2026, 7, 1));

        ArgumentCaptor<LocalDateTime> endCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(transactionRepository).findByUsernameAndDeletedAtIsNullAndTransactionDateBetweenOrderByTransactionDateDesc(
                eq("alice"), eq(LocalDate.of(2026, 7, 1).atStartOfDay()), endCaptor.capture());
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
        when(transactionRepository.findByUsernameAndDeletedAtIsNullOrderByTransactionDateDesc("alice"))
                .thenReturn(List.of(t));

        List<TransactionResponseDto> result = transactionService.getHistory("alice", null, null);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().merchantName()).isEqualTo("Warung Alice");
        assertThat(result.getFirst().issuerName()).isEqualTo("GoPay");
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
}
