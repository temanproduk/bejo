package com.bejo.service;

import com.bejo.dto.request.QrMpmNotifyRequestDto;
import com.bejo.dto.response.QrMpmNotifyResponseDto;
import com.bejo.entity.Transaction;
import com.bejo.entity.User;
import com.bejo.exception.ResourceNotFoundException;
import com.bejo.repository.TransactionRepository;
import com.bejo.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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
class QrisCallbackServiceTest {

    @Mock private TransactionRepository transactionRepository;
    @Mock private UserRepository userRepository;
    @Mock private PushNotificationService pushNotificationService;

    private QrisCallbackService service;

    @BeforeEach
    void setUp() {
        service = new QrisCallbackService(transactionRepository, userRepository,
                pushNotificationService, new ObjectMapper(), new SimpleMeterRegistry());
        ReflectionTestUtils.setField(service, "defaultUsername", "admin");
    }

    private QrMpmNotifyRequestDto request(String status) {
        return new QrMpmNotifyRequestDto(
                "XCBS6Z450S", "DEV20250826422460", status, "SUCCESS",
                "9360082230250015792", "9360082232504524231", "Asep & Testing", null,
                new QrMpmNotifyRequestDto.Amount("100.00", "IDR"),
                new QrMpmNotifyRequestDto.AdditionalInfo("0.00", "AstraPay", "POS", null, "5164",
                        "AstraPay User", "9360082232504524231", "9360082230250015792",
                        "93600822", "INV/QRM/4542BR/250826/2G4Y6CCZ"));
    }

    @Test
    void recordsTransactionMappedFromCallback() {
        User u = new User();
        u.setUsername("alice");
        when(userRepository.findFirstByNmid("9360082232504524231")).thenReturn(Optional.of(u));
        when(transactionRepository.findByIdempotencyKeyAndUsername(any(), eq("alice")))
                .thenReturn(Optional.empty());

        QrMpmNotifyResponseDto res = service.processNotify(request("00"), "2020-12-23T08:46:11+07:00");

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        Transaction t = captor.getValue();
        assertThat(t.getUsername()).isEqualTo("alice");
        assertThat(t.getTransactionId()).isEqualTo("INV/QRM/4542BR/250826/2G4Y6CCZ");
        assertThat(t.getIdempotencyKey()).isEqualTo("XCBS6Z450S|DEV20250826422460");
        assertThat(t.getInvoiceId()).isEqualTo("XCBS6Z450S");
        assertThat(t.getAmount()).isEqualTo(100L);
        assertThat(t.getStatus()).isEqualTo("SUCCESS");
        assertThat(t.getType()).isEqualTo("MANAGED");
        assertThat(t.getAcquirer()).isEqualTo("AstraPay");
        assertThat(t.getMerchantPan()).isEqualTo("9360082232504524231");
        assertThat(t.getAdditionalInfo()).contains("originalReferenceNo").contains("XCBS6Z450S");

        assertThat(res.billNumber()).isEqualTo("XCBS6Z450S");
        assertThat(res.status()).isEqualTo("SUCCESS");
    }

    @Test
    void idempotentReplayReturnsExistingWithoutSaving() {
        User u = new User();
        u.setUsername("alice");
        Transaction existing = new Transaction();
        existing.setTransactionId("INV/QRM/4542BR/250826/2G4Y6CCZ");
        existing.setStatus("SUCCESS");
        when(userRepository.findFirstByNmid("9360082232504524231")).thenReturn(Optional.of(u));
        when(transactionRepository.findByIdempotencyKeyAndUsername("XCBS6Z450S|DEV20250826422460", "alice"))
                .thenReturn(Optional.of(existing));

        QrMpmNotifyResponseDto res = service.processNotify(request("00"), null);

        assertThat(res.billNumber()).isEqualTo("XCBS6Z450S");
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void fallsBackToDefaultUserWhenNmidDoesNotMatch() {
        User admin = new User();
        admin.setUsername("admin");
        when(userRepository.findFirstByNmid("9360082232504524231")).thenReturn(Optional.empty());
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(transactionRepository.findByIdempotencyKeyAndUsername(any(), eq("admin")))
                .thenReturn(Optional.empty());

        service.processNotify(request("00"), null);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getUsername()).isEqualTo("admin");
    }

    @Test
    void unknownMerchantAndNoDefaultUserIsRejected() {
        when(userRepository.findFirstByNmid(any())).thenReturn(Optional.empty());
        when(userRepository.findByUsername("admin")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.processNotify(request("00"), null))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void mapsStatusCodesOntoStoredTransaction() {
        User u = new User();
        u.setUsername("alice");
        when(userRepository.findFirstByNmid(any())).thenReturn(Optional.of(u));
        when(transactionRepository.findByIdempotencyKeyAndUsername(any(), any())).thenReturn(Optional.empty());

        service.processNotify(request("00"), null);
        service.processNotify(request("02"), null);
        service.processNotify(request("03"), null);
        service.processNotify(request("05"), null);
        service.processNotify(request("99"), null);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository, times(5)).save(captor.capture());
        assertThat(captor.getAllValues()).extracting(Transaction::getStatus)
                .containsExactly("SUCCESS", "FAILED", "REFUNDED", "EXPIRED", "UNKNOWN");
    }

    @Test
    void responseStatusIsAcknowledgmentNotPaymentStatus() {
        User u = new User();
        u.setUsername("alice");
        when(userRepository.findFirstByNmid(any())).thenReturn(Optional.of(u));
        when(transactionRepository.findByIdempotencyKeyAndUsername(any(), any())).thenReturn(Optional.empty());

        // Payment FAILED (02), but the callback ack is still SUCCESS per the spec
        QrMpmNotifyResponseDto res = service.processNotify(request("02"), null);
        assertThat(res.status()).isEqualTo("SUCCESS");
    }
}
