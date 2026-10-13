package com.bejo.core.service;

import com.bejo.core.domain.PasswordResetToken;
import com.bejo.core.domain.User;
import com.bejo.core.repository.PasswordResetTokenRepository;
import com.bejo.core.repository.UserRepository;
import com.bejo.notification.EmailDeliveryException;
import com.bejo.notification.EmailSender;
import com.bejo.shared.exception.ServiceUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PasswordResetServiceTest {

    private static final Long USER_ID = 7L;

    @Mock private UserRepository userRepository;
    @Mock private PasswordResetTokenRepository tokenRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private EmailSender emailSender;
    @Mock private LoginThrottleService loginThrottle;

    private PasswordResetService service;
    private User user;

    @BeforeEach
    void setUp() {
        // A mock transaction manager is enough: the template just runs the callback
        TransactionTemplate tx = new TransactionTemplate(mock(PlatformTransactionManager.class));
        service = new PasswordResetService(userRepository, tokenRepository, passwordEncoder,
                emailSender, loginThrottle, tx);
        ReflectionTestUtils.setField(service, "pepperSource", "test-secret");
        ReflectionTestUtils.setField(service, "codeTtlMinutes", 15);
        ReflectionTestUtils.setField(service, "maxAttempts", 5);
        ReflectionTestUtils.setField(service, "resendCooldownSeconds", 60);
        ReflectionTestUtils.setField(service, "maxRequestsPerHour", 5);
        ReflectionTestUtils.setField(service, "emailChangeLockoutHours", 24);
        ReflectionTestUtils.setField(service, "responseFloorMs", 0L);

        user = new User();
        ReflectionTestUtils.setField(user, "id", USER_ID);
        user.setUsername("budi");
        user.setName("Budi");
        user.setEmail("budi@example.com");
        user.setPasswordHash("old-hash");
        lenient().when(emailSender.isEnabled()).thenReturn(true);
        lenient().when(userRepository.findByUsernameAndDeletedAtIsNull("budi")).thenReturn(Optional.of(user));
    }

    // ---------- requesting a code ----------

    /** Stubs the rate-limit lookups as "nothing recent", so a request is eligible. */
    private void noRecentRequests() {
        when(tokenRepository.findFirstByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(Optional.empty());
        when(tokenRepository.countByUserIdAndCreatedAtAfter(eq(USER_ID), any())).thenReturn(0L);
    }

    private String sentCode() {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(eq("budi@example.com"), eq(PasswordResetService.SUBJECT), body.capture());
        Matcher m = Pattern.compile("Kode reset Anda: (\\d{6})").matcher(body.getValue());
        assertThat(m.find()).as("the email carries a 6-digit code").isTrue();
        return m.group(1);
    }

    @Test
    void refusesWhenEmailIsNotConfigured() {
        when(emailSender.isEnabled()).thenReturn(false);

        assertThatThrownBy(() -> service.requestReset("budi"))
                .isInstanceOf(ServiceUnavailableException.class);
        verify(userRepository, never()).findByUsernameAndDeletedAtIsNull(anyString());
    }

    @Test
    void anUnknownUsernameGetsNoEmailAndNoError() {
        when(userRepository.findByUsernameAndDeletedAtIsNull("ghost")).thenReturn(Optional.empty());

        assertThatCode(() -> service.requestReset("ghost")).doesNotThrowAnyException();

        verify(emailSender, never()).send(anyString(), anyString(), anyString());
        verify(tokenRepository, never()).save(any());
    }

    @Test
    void anEligibleUserGetsAHashedCodeStoredAndTheCodeEmailed() {
        noRecentRequests();

        service.requestReset("  budi  ");   // surrounding whitespace is tolerated

        ArgumentCaptor<PasswordResetToken> saved = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository).save(saved.capture());
        String code = sentCode();
        assertThat(saved.getValue().getCodeHash()).hasSize(64).doesNotContain(code);   // keyed hash, not the code
        assertThat(saved.getValue().getExpiresAt())
                .isBetween(LocalDateTime.now().plusMinutes(14), LocalDateTime.now().plusMinutes(16));
        verify(tokenRepository).invalidateOpen(eq(USER_ID), any());   // a new code replaces any open one
    }

    @Test
    void noCodeIsSentToAnEmailChangedRecently() {
        user.setEmailChangedAt(LocalDateTime.now().minusHours(1));

        service.requestReset("budi");

        verify(emailSender, never()).send(anyString(), anyString(), anyString());
        verify(tokenRepository, never()).save(any());
    }

    @Test
    void anOldEmailChangeNoLongerBlocks() {
        user.setEmailChangedAt(LocalDateTime.now().minusHours(25));
        noRecentRequests();

        service.requestReset("budi");

        sentCode();
    }

    @Test
    void theResendCooldownSuppressesASecondCode() {
        PasswordResetToken recent = new PasswordResetToken();
        recent.setCreatedAt(LocalDateTime.now().minusSeconds(10));
        when(tokenRepository.findFirstByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(Optional.of(recent));

        service.requestReset("budi");

        verify(emailSender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void theHourlyCapSuppressesFurtherCodes() {
        when(tokenRepository.findFirstByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(Optional.empty());
        when(tokenRepository.countByUserIdAndCreatedAtAfter(eq(USER_ID), any())).thenReturn(5L);

        service.requestReset("budi");

        verify(emailSender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void aMailServerFailureLooksLikeSuccessToTheCaller() {
        noRecentRequests();
        doThrow(new EmailDeliveryException("smtp down", null)).when(emailSender).send(anyString(), anyString(), anyString());

        assertThatCode(() -> service.requestReset("budi")).doesNotThrowAnyException();
    }

    // ---------- resetting with a code ----------

    private PasswordResetToken openToken(String codeHash) {
        PasswordResetToken t = new PasswordResetToken();
        ReflectionTestUtils.setField(t, "id", 99L);
        t.setUserId(USER_ID);
        t.setCodeHash(codeHash);
        t.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        return t;
    }

    /** Issues a real code through requestReset and returns {code, stored token}. */
    private Object[] issuedCode() {
        noRecentRequests();
        service.requestReset("budi");
        ArgumentCaptor<PasswordResetToken> saved = ArgumentCaptor.forClass(PasswordResetToken.class);
        verify(tokenRepository).save(saved.capture());
        PasswordResetToken token = saved.getValue();
        ReflectionTestUtils.setField(token, "id", 99L);
        lenient().when(tokenRepository.findFirstByUserIdAndUsedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(eq(USER_ID), any()))
                .thenReturn(Optional.of(token));
        return new Object[]{sentCode(), token};
    }

    private void stubFreshUser() {
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));
        when(passwordEncoder.encode("NewPass123")).thenReturn("new-hash");
    }

    @Test
    void theRightCodeSetsTheNewPasswordAndSpendsEveryOpenCode() {
        Object[] issued = issuedCode();
        when(tokenRepository.spendAttempt(eq(99L), any(), eq(5))).thenReturn(1);
        when(tokenRepository.markUsed(eq(99L), any())).thenReturn(1);
        stubFreshUser();

        service.resetPassword("budi", (String) issued[0], "NewPass123");

        assertThat(user.getPasswordHash()).isEqualTo("new-hash");
        verify(userRepository).save(user);
        // once when the code was issued (retiring older ones), once now (the reset spends them all)
        verify(tokenRepository, times(2)).invalidateOpen(eq(USER_ID), any());
        verify(loginThrottle).recordSuccess("budi");   // also lifts a login lockout
    }

    @Test
    void aWrongCodeFailsButStillCostsAnAttempt() {
        issuedCode();
        when(tokenRepository.spendAttempt(eq(99L), any(), eq(5))).thenReturn(1);

        assertThatThrownBy(() -> service.resetPassword("budi", "000000", "NewPass123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(PasswordResetService.INVALID_CODE);

        verify(tokenRepository).spendAttempt(eq(99L), any(), eq(5));   // counted before comparing
        verify(tokenRepository, never()).markUsed(anyLong(), any());
        verify(passwordEncoder, never()).encode(anyString());
        assertThat(user.getPasswordHash()).isEqualTo("old-hash");
    }

    @Test
    void aCodeOutOfAttemptsIsRejectedEvenIfTheCodeIsRight() {
        Object[] issued = issuedCode();
        when(tokenRepository.spendAttempt(eq(99L), any(), eq(5))).thenReturn(0);   // limit reached

        assertThatThrownBy(() -> service.resetPassword("budi", (String) issued[0], "NewPass123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(PasswordResetService.INVALID_CODE);
        verify(passwordEncoder, never()).encode(anyString());
    }

    @Test
    void anUnknownUserAndAMissingOpenCodeGiveTheSameMessage() {
        when(userRepository.findByUsernameAndDeletedAtIsNull("ghost")).thenReturn(Optional.empty());
        when(tokenRepository.findFirstByUserIdAndUsedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(eq(USER_ID), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resetPassword("ghost", "123456", "NewPass123"))
                .hasMessage(PasswordResetService.INVALID_CODE);
        assertThatThrownBy(() -> service.resetPassword("budi", "123456", "NewPass123"))
                .hasMessage(PasswordResetService.INVALID_CODE);
    }

    @Test
    void losingARaceToAnotherSubmitChangesNothing() {
        Object[] issued = issuedCode();
        when(tokenRepository.spendAttempt(eq(99L), any(), eq(5))).thenReturn(1);
        when(tokenRepository.markUsed(eq(99L), any())).thenReturn(0);   // someone else spent it first

        assertThatThrownBy(() -> service.resetPassword("budi", (String) issued[0], "NewPass123"))
                .hasMessage(PasswordResetService.INVALID_CODE);

        verify(userRepository, never()).save(any());
        verify(loginThrottle, never()).recordSuccess(anyString());
    }

    @Test
    void aCodeIssuedForOneUserDoesNotWorkForAnother() {
        // The hash binds the code to the user id, so the same digits for user 8 do not match user 7's token
        Object[] issued = issuedCode();
        User other = new User();
        ReflectionTestUtils.setField(other, "id", 8L);
        other.setUsername("siti");
        when(userRepository.findByUsernameAndDeletedAtIsNull("siti")).thenReturn(Optional.of(other));
        when(tokenRepository.findFirstByUserIdAndUsedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(eq(8L), any()))
                .thenReturn(Optional.of(openToken(((PasswordResetToken) issued[1]).getCodeHash())));
        when(tokenRepository.spendAttempt(eq(99L), any(), anyInt())).thenReturn(1);

        assertThatThrownBy(() -> service.resetPassword("siti", (String) issued[0], "NewPass123"))
                .hasMessage(PasswordResetService.INVALID_CODE);
    }
}
