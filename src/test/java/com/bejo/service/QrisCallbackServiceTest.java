package com.bejo.service;

import com.bejo.dto.request.QrMpmNotifyRequestDto;
import com.bejo.dto.response.QrMpmNotifyResponseDto;
import com.bejo.entity.Business;
import com.bejo.entity.Transaction;
import com.bejo.entity.User;
import com.bejo.exception.ResourceNotFoundException;
import com.bejo.repository.BusinessRepository;
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

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * PoC behavior: every callback records a MANAGED transaction against the seed user's
 * business and pushes to every device of every user in that business, regardless of the
 * (currently buggy) payload contents.
 */
@ExtendWith(MockitoExtension.class)
class QrisCallbackServiceTest {

    private static final Long BIZ = 1L;

    @Mock private TransactionRepository transactionRepository;
    @Mock private UserRepository userRepository;
    @Mock private BusinessRepository businessRepository;
    @Mock private PushNotificationService pushNotificationService;

    private QrisCallbackService service;

    @BeforeEach
    void setUp() {
        service = new QrisCallbackService(transactionRepository, userRepository, businessRepository,
                pushNotificationService, new ObjectMapper(), new SimpleMeterRegistry());
        ReflectionTestUtils.setField(service, "defaultUsername", "admin");
    }

    private void seedUserExists() {
        User admin = new User();
        admin.setUsername("admin");
        admin.setBusinessId(BIZ);
        Business business = new Business();
        business.setName("Seed Merchant");
        lenient().when(userRepository.findByUsernameAndDeletedAtIsNull("admin")).thenReturn(Optional.of(admin));
        lenient().when(businessRepository.findById(BIZ)).thenReturn(Optional.of(business));
    }

    // Full (well-formed) payload
    private QrMpmNotifyRequestDto request(String status) {
        return new QrMpmNotifyRequestDto(
                "XCBS6Z450S", "DEV20250826422460", status, "SUCCESS",
                "9360082230250015792", "9360082232504524231", "Asep & Testing", null,
                new QrMpmNotifyRequestDto.Amount("100.00", "IDR"),
                new QrMpmNotifyRequestDto.AdditionalInfo("0.00", "AstraPay", "POS", null, "5164",
                        "AstraPay User", "9360082232504524231", "9360082230250015792",
                        "93600822", "INV/QRM/4542BR/250826/2G4Y6CCZ"));
    }

    // The buggy provider payload — only amount + status, everything else null
    private QrMpmNotifyRequestDto buggyMinimal() {
        return new QrMpmNotifyRequestDto(
                null, null, "00", "SUCCESS", null, null, null, null,
                new QrMpmNotifyRequestDto.Amount("1002.00", "IDR"),
                new QrMpmNotifyRequestDto.AdditionalInfo(null, null, null, null, null, null, null, null, null, null));
    }

    @Test
    void recordsManagedTransactionForSeedBusiness() {
        seedUserExists();

        QrMpmNotifyResponseDto res = service.processNotify(request("00"), "2020-12-23T08:46:11+07:00");

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        Transaction t = captor.getValue();
        assertThat(t.getBusinessId()).isEqualTo(BIZ);              // always the seed user's business
        assertThat(t.getTransactionId()).startsWith("TXN-");       // generated, not from payload
        assertThat(t.getIdempotencyKey()).isNull();                // PoC: no dedupe
        assertThat(t.getAmount()).isEqualTo(100L);
        assertThat(t.getStatus()).isEqualTo("SUCCESS");
        assertThat(t.getType()).isEqualTo("MANAGED");
        assertThat(res.status()).isEqualTo("SUCCESS");             // ack, not payment status
    }

    @Test
    void transactionDateDefaultsToNow() {
        seedUserExists();

        // PoC: X-TIMESTAMP parsing is currently disabled, so the stored time is now().
        LocalDateTime before = LocalDateTime.now().minusMinutes(1);
        service.processNotify(request("00"), "2020-12-23T01:46:11Z");

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getTransactionDate()).isAfter(before);
    }

    @Test
    void pushesToEveryDeviceInTheBusiness() {
        seedUserExists();
        service.processNotify(request("00"), null);
        verify(pushNotificationService).sendToBusiness(eq(BIZ), any());
    }

    @Test
    void everyCallbackCreatesANewTransaction() {
        seedUserExists();

        service.processNotify(request("00"), null);
        service.processNotify(request("00"), null);

        verify(transactionRepository, times(2)).save(any(Transaction.class));
    }

    @Test
    void buggyMinimalPayloadIsStillRecorded() {
        seedUserExists();

        service.processNotify(buggyMinimal(), null);

        ArgumentCaptor<Transaction> captor = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(captor.capture());
        assertThat(captor.getValue().getAmount()).isEqualTo(1002L);
        assertThat(captor.getValue().getBusinessId()).isEqualTo(BIZ);
    }

    @Test
    void mapsStatusCodesOntoStoredTransaction() {
        seedUserExists();

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
    void seedUserMissingIsRejected() {
        when(userRepository.findByUsernameAndDeletedAtIsNull("admin")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.processNotify(request("00"), null))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(transactionRepository, never()).save(any());
    }
}
