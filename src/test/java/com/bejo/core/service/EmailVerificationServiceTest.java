package com.bejo.core.service;

import com.bejo.core.domain.BusinessRole;
import com.bejo.core.domain.EmailVerificationToken;
import com.bejo.core.domain.User;
import com.bejo.core.repository.EmailVerificationTokenRepository;
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
class EmailVerificationServiceTest {

    private static final Long USER_ID = 7L;

    @Mock private UserRepository userRepository;
    @Mock private EmailVerificationTokenRepository tokenRepository;
    @Mock private EmailSender emailSender;

    private EmailVerificationService service;
    private User user;

    @BeforeEach
    void setUp() {
        // A mock transaction manager is enough: the template just runs the callback
        TransactionTemplate tx = new TransactionTemplate(mock(PlatformTransactionManager.class));
        service = new EmailVerificationService(userRepository, tokenRepository, emailSender, tx);
        ReflectionTestUtils.setField(service, "pepperSource", "test-secret");
        ReflectionTestUtils.setField(service, "codeTtlMinutes", 15);
        ReflectionTestUtils.setField(service, "maxAttempts", 5);
        ReflectionTestUtils.setField(service, "resendCooldownSeconds", 60);
        ReflectionTestUtils.setField(service, "maxRequestsPerHour", 5);
        ReflectionTestUtils.setField(service, "responseFloorMs", 0L);

        user = new User();
        ReflectionTestUtils.setField(user, "id", USER_ID);
        user.setUsername("budi");
        user.setName("Budi");
        user.setEmail("budi@example.com");
        user.setBusinessRole(BusinessRole.SUPER_ADMIN);   // verification is for owners; sub-accounts are exempt
        user.setEmailVerified(false);
        lenient().when(emailSender.isEnabled()).thenReturn(true);
        lenient().when(userRepository.findByUsernameAndDeletedAtIsNull("budi")).thenReturn(Optional.of(user));
    }

    private void noRecentRequests() {
        when(tokenRepository.findFirstByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(Optional.empty());
        when(tokenRepository.countByUserIdAndCreatedAtAfter(eq(USER_ID), any())).thenReturn(0L);
    }

    private String sentCode() {
        ArgumentCaptor<String> body = ArgumentCaptor.forClass(String.class);
        verify(emailSender).send(eq("budi@example.com"), eq(EmailVerificationService.SUBJECT), body.capture());
        Matcher m = Pattern.compile("Kode verifikasi Anda: (\\d{6})").matcher(body.getValue());
        assertThat(m.find()).as("the email carries a 6-digit code").isTrue();
        return m.group(1);
    }

    // ---------- sending a code ----------

    @Test
    void anUnverifiedUserGetsAHashedCodeStoredAndTheCodeEmailed() {
        noRecentRequests();

        boolean sent = service.sendCode("budi");

        assertThat(sent).isTrue();
        ArgumentCaptor<EmailVerificationToken> saved = ArgumentCaptor.forClass(EmailVerificationToken.class);
        verify(tokenRepository).save(saved.capture());
        String code = sentCode();
        assertThat(saved.getValue().getCodeHash()).hasSize(64).doesNotContain(code);   // keyed hash, not the code
        assertThat(saved.getValue().getExpiresAt())
                .isBetween(LocalDateTime.now().plusMinutes(14), LocalDateTime.now().plusMinutes(16));
        verify(tokenRepository).invalidateOpen(eq(USER_ID), any());   // a new code replaces any open one
    }

    @Test
    void anAlreadyVerifiedUserIsNotSentAnything() {
        user.setEmailVerified(true);

        assertThat(service.sendCode("budi")).isFalse();

        verify(emailSender, never()).send(anyString(), anyString(), anyString());
        verify(tokenRepository, never()).save(any());
    }

    @Test
    void aSubAccountIsNeverSentACode() {
        // Sub-accounts never need to verify, even a legacy one stored with the flag still false
        user.setBusinessRole(BusinessRole.MEMBER);

        assertThat(service.sendCode("budi")).isFalse();

        verify(emailSender, never()).send(anyString(), anyString(), anyString());
        verify(tokenRepository, never()).save(any());
    }

    @Test
    void noCodeIsSentWhenEmailIsNotConfigured() {
        when(emailSender.isEnabled()).thenReturn(false);

        assertThat(service.sendCode("budi")).isFalse();

        verify(emailSender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void theResendCooldownSuppressesASecondCode() {
        EmailVerificationToken recent = new EmailVerificationToken();
        recent.setCreatedAt(LocalDateTime.now().minusSeconds(10));
        when(tokenRepository.findFirstByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(Optional.of(recent));

        assertThat(service.sendCode("budi")).isFalse();

        verify(emailSender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void theHourlyCapSuppressesFurtherCodes() {
        when(tokenRepository.findFirstByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(Optional.empty());
        when(tokenRepository.countByUserIdAndCreatedAtAfter(eq(USER_ID), any())).thenReturn(5L);

        assertThat(service.sendCode("budi")).isFalse();

        verify(emailSender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void aMailServerFailureNeverBreaksTheCaller() {
        noRecentRequests();
        doThrow(new EmailDeliveryException("smtp down", null)).when(emailSender).send(anyString(), anyString(), anyString());

        assertThat(service.sendCode("budi")).isFalse();   // registration must still succeed; the user can resend
    }

    @Test
    void aCodeThatNeverLeftIsForgottenSoItCostsNoCooldownOrHourlyBudget() {
        noRecentRequests();
        doThrow(new EmailDeliveryException("smtp down", null)).when(emailSender).send(anyString(), anyString(), anyString());

        service.sendCode("budi");

        ArgumentCaptor<EmailVerificationToken> saved = ArgumentCaptor.forClass(EmailVerificationToken.class);
        verify(tokenRepository).save(saved.capture());
        verify(tokenRepository).delete(saved.getValue());   // the very token that was stored is removed again
    }

    @Test
    void aNewAddressGetsItsCodeEvenInsideTheCooldownOfThePreviousOne() {
        EmailVerificationToken recent = new EmailVerificationToken();
        recent.setCreatedAt(LocalDateTime.now().minusSeconds(10));
        when(tokenRepository.findFirstByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(Optional.of(recent));
        when(tokenRepository.countByUserIdAndCreatedAtAfter(eq(USER_ID), any())).thenReturn(1L);

        assertThat(service.sendCode("budi", true)).isTrue();
        sentCode();
    }

    @Test
    void aNewAddressIsStillSubjectToTheHourlyCap() {
        when(tokenRepository.findFirstByUserIdOrderByCreatedAtDesc(USER_ID)).thenReturn(Optional.empty());
        when(tokenRepository.countByUserIdAndCreatedAtAfter(eq(USER_ID), any())).thenReturn(5L);

        assertThat(service.sendCode("budi", true)).isFalse();   // changing the address repeatedly cannot flood a mailbox

        verify(emailSender, never()).send(anyString(), anyString(), anyString());
    }

    // ---------- resend (the public endpoint) ----------

    @Test
    void resendRefusesWhenEmailIsNotConfigured() {
        when(emailSender.isEnabled()).thenReturn(false);

        assertThatThrownBy(() -> service.resend("budi")).isInstanceOf(ServiceUnavailableException.class);
        verify(userRepository, never()).findByUsernameAndDeletedAtIsNull(anyString());
    }

    @Test
    void resendForAnUnknownUsernameGetsNoEmailAndNoError() {
        when(userRepository.findByUsernameAndDeletedAtIsNull("ghost")).thenReturn(Optional.empty());

        assertThatCode(() -> service.resend("ghost")).doesNotThrowAnyException();

        verify(emailSender, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void resendToleratesSurroundingWhitespaceAndSendsACode() {
        noRecentRequests();

        service.resend("  budi  ");

        sentCode();
    }

    @Test
    void resendOnAMailFailureLooksLikeSuccess() {
        noRecentRequests();
        doThrow(new EmailDeliveryException("smtp down", null)).when(emailSender).send(anyString(), anyString(), anyString());

        assertThatCode(() -> service.resend("budi")).doesNotThrowAnyException();
    }

    // ---------- verifying with a code ----------

    private EmailVerificationToken openToken(String codeHash) {
        EmailVerificationToken t = new EmailVerificationToken();
        ReflectionTestUtils.setField(t, "id", 99L);
        t.setUserId(USER_ID);
        t.setCodeHash(codeHash);
        t.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        return t;
    }

    /** Issues a real code through sendCode and returns {code, stored token}. */
    private Object[] issuedCode() {
        noRecentRequests();
        service.sendCode("budi");
        ArgumentCaptor<EmailVerificationToken> saved = ArgumentCaptor.forClass(EmailVerificationToken.class);
        verify(tokenRepository).save(saved.capture());
        EmailVerificationToken token = saved.getValue();
        ReflectionTestUtils.setField(token, "id", 99L);
        lenient().when(tokenRepository.findFirstByUserIdAndUsedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(eq(USER_ID), any()))
                .thenReturn(Optional.of(token));
        return new Object[]{sentCode(), token};
    }

    @Test
    void theRightCodeMarksTheEmailVerifiedAndSpendsEveryOpenCode() {
        Object[] issued = issuedCode();
        when(tokenRepository.spendAttempt(eq(99L), any(), eq(5))).thenReturn(1);
        when(tokenRepository.markUsed(eq(99L), any())).thenReturn(1);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(user));

        service.verify("budi", (String) issued[0]);

        assertThat(user.isEmailVerified()).isTrue();
        verify(userRepository).save(user);
        // once when the code was issued (retiring older ones), once now (verifying spends them all)
        verify(tokenRepository, times(2)).invalidateOpen(eq(USER_ID), any());
    }

    @Test
    void aWrongCodeFailsButStillCostsAnAttempt() {
        issuedCode();
        when(tokenRepository.spendAttempt(eq(99L), any(), eq(5))).thenReturn(1);

        assertThatThrownBy(() -> service.verify("budi", "000000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage(EmailVerificationService.INVALID_CODE);

        verify(tokenRepository).spendAttempt(eq(99L), any(), eq(5));   // counted before comparing
        verify(tokenRepository, never()).markUsed(anyLong(), any());
        assertThat(user.isEmailVerified()).isFalse();
    }

    @Test
    void aCodeOutOfAttemptsIsRejectedEvenIfTheCodeIsRight() {
        Object[] issued = issuedCode();
        when(tokenRepository.spendAttempt(eq(99L), any(), eq(5))).thenReturn(0);   // limit reached

        assertThatThrownBy(() -> service.verify("budi", (String) issued[0]))
                .hasMessage(EmailVerificationService.INVALID_CODE);
        assertThat(user.isEmailVerified()).isFalse();
    }

    @Test
    void anUnknownUserAndAMissingOpenCodeGiveTheSameMessage() {
        when(userRepository.findByUsernameAndDeletedAtIsNull("ghost")).thenReturn(Optional.empty());
        when(tokenRepository.findFirstByUserIdAndUsedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(eq(USER_ID), any()))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.verify("ghost", "123456")).hasMessage(EmailVerificationService.INVALID_CODE);
        assertThatThrownBy(() -> service.verify("budi", "123456")).hasMessage(EmailVerificationService.INVALID_CODE);
    }

    @Test
    void losingARaceToAnotherSubmitChangesNothing() {
        Object[] issued = issuedCode();
        when(tokenRepository.spendAttempt(eq(99L), any(), eq(5))).thenReturn(1);
        when(tokenRepository.markUsed(eq(99L), any())).thenReturn(0);   // someone else spent it first

        assertThatThrownBy(() -> service.verify("budi", (String) issued[0]))
                .hasMessage(EmailVerificationService.INVALID_CODE);

        verify(userRepository, never()).save(any());
        assertThat(user.isEmailVerified()).isFalse();
    }

    @Test
    void aCodeSentToTheOldAddressDoesNotVerifyTheNewOne() {
        // The hash covers the address: after the user changes their email the earlier code (still "open")
        // must not confirm the new address, even though the digits were right when it was issued.
        Object[] issued = issuedCode();
        user.setEmail("new-address@example.com");
        when(tokenRepository.spendAttempt(eq(99L), any(), eq(5))).thenReturn(1);

        assertThatThrownBy(() -> service.verify("budi", (String) issued[0]))
                .hasMessage(EmailVerificationService.INVALID_CODE);
        assertThat(user.isEmailVerified()).isFalse();
    }

    @Test
    void aCodeIssuedForOneUserDoesNotWorkForAnother() {
        Object[] issued = issuedCode();
        User other = new User();
        ReflectionTestUtils.setField(other, "id", 8L);
        other.setUsername("siti");
        other.setEmail("budi@example.com");   // even the same address
        when(userRepository.findByUsernameAndDeletedAtIsNull("siti")).thenReturn(Optional.of(other));
        when(tokenRepository.findFirstByUserIdAndUsedAtIsNullAndExpiresAtAfterOrderByCreatedAtDesc(eq(8L), any()))
                .thenReturn(Optional.of(openToken(((EmailVerificationToken) issued[1]).getCodeHash())));
        when(tokenRepository.spendAttempt(eq(99L), any(), eq(5))).thenReturn(1);

        assertThatThrownBy(() -> service.verify("siti", (String) issued[0]))
                .hasMessage(EmailVerificationService.INVALID_CODE);
    }

    // ---------- helpers ----------

    @Test
    void maskedEmailShowsEnoughToRecogniseButNotToHarvest() {
        assertThat(EmailVerificationService.maskEmail("budi@example.com")).isEqualTo("b***@example.com");
        assertThat(EmailVerificationService.maskEmail("x")).isEqualTo("***");
        assertThat(EmailVerificationService.maskEmail(null)).isNull();
        assertThat(EmailVerificationService.maskEmail(" ")).isNull();
    }

    @Test
    void retiringOpenCodesWhenTheAddressChanges() {
        service.invalidateOpenCodes(USER_ID);

        verify(tokenRepository).invalidateOpen(eq(USER_ID), any());
    }
}
