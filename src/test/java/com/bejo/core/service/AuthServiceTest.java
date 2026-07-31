package com.bejo.core.service;

import com.bejo.core.domain.BusinessRole;
import com.bejo.core.dto.LoginRequestDto;
import com.bejo.core.dto.AccountSettingsResponseDto;
import com.bejo.core.dto.LoginResponseDto;
import com.bejo.core.domain.Business;
import com.bejo.core.domain.BusinessBank;
import com.bejo.core.domain.Session;
import com.bejo.core.domain.User;
import com.bejo.core.repository.BusinessBankRepository;
import com.bejo.core.repository.BusinessRepository;
import com.bejo.core.repository.SessionRepository;
import com.bejo.core.repository.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    private static final Long BIZ = 1L;

    @Mock private UserRepository userRepository;
    @Mock private BusinessRepository businessRepository;
    @Mock private BusinessBankRepository businessBankRepository;
    @Mock private SessionRepository sessionRepository;
    @Mock private QrisService qrisService;
    @Mock private AccountSettingsService accountSettingsService;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;
    @Mock private TokenBlacklistService tokenBlacklistService;
    @Mock private LoginThrottleService loginThrottleService;

    private SimpleMeterRegistry meterRegistry;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        authService = new AuthService(userRepository, businessRepository, businessBankRepository,
                sessionRepository, qrisService, accountSettingsService, passwordEncoder, jwtService, tokenBlacklistService,
                loginThrottleService, meterRegistry);
        lenient().when(accountSettingsService.getSettings("alice")).thenReturn(
                new AccountSettingsResponseDto(Map.of(), List.of(), List.of(), true, false, false, false, false, false));
    }

    private User user(String username) {
        User u = new User();
        u.setUsername(username);
        u.setPasswordHash("$hash");
        u.setName("Alice");
        u.setEmail("alice@example.com");
        u.setPhone("+628111111111");
        u.setBusinessId(BIZ);
        u.setBusinessRole(BusinessRole.SUPER_ADMIN);
        u.setAccountType("MANAGED");
        return u;
    }

    private Business business() {
        Business b = new Business();
        b.setName("Warung Alice");
        b.setAddress("Jakarta");
        b.setSize("Mikro");
        b.setIndustry("Makanan & Minuman");
        return b;
    }

    private BusinessBank bank() {
        BusinessBank b = new BusinessBank();
        b.setBusinessId(BIZ);
        b.setBankName("BCA");
        b.setBankNumber("1234567890");
        b.setBankAccountName("Alice");
        return b;
    }

    @Test
    void loginWithUnknownUserFailsAndCountsFailure() {
        when(userRepository.findByUsernameAndDeletedAtIsNull("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequestDto("ghost", "pw", "dev-1", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Nama pengguna atau kata sandi salah");

        assertThat(meterRegistry.counter("login.attempts", "result", "failure").count()).isEqualTo(1.0);
    }

    @Test
    void loginWithWrongPasswordFails() {
        when(userRepository.findByUsernameAndDeletedAtIsNull("alice")).thenReturn(Optional.of(user("alice")));
        when(passwordEncoder.matches("wrong", "$hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequestDto("alice", "wrong", "dev-1", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Nama pengguna atau kata sandi salah");

        assertThat(meterRegistry.counter("login.attempts", "result", "failure").count()).isEqualTo(1.0);
    }

    @Test
    void successfulLoginReturnsTokenAndCreatesSession() {
        when(userRepository.findByUsernameAndDeletedAtIsNull("alice")).thenReturn(Optional.of(user("alice")));
        when(passwordEncoder.matches("pw", "$hash")).thenReturn(true);
        when(businessRepository.findById(BIZ)).thenReturn(Optional.of(business()));
        when(businessBankRepository.findByBusinessId(BIZ)).thenReturn(Optional.of(bank()));
        when(sessionRepository.findByUsernameAndDeviceId("alice", "dev-1")).thenReturn(Optional.empty());
        when(jwtService.generateToken("alice", "$hash")).thenReturn("jwt-token");
        when(jwtService.getExpiresInSeconds()).thenReturn(86_400L);

        LoginResponseDto data = authService.login(new LoginRequestDto("alice", "pw", "dev-1", "fcm-token"));

        assertThat(data.token()).isEqualTo("jwt-token");
        assertThat(data.tokenType()).isEqualTo("Bearer");
        assertThat(data.expiresIn()).isEqualTo(86_400L);
        assertThat(data.bankName()).isEqualTo("BCA");
        assertThat(data.businessName()).isEqualTo("Warung Alice");
        assertThat(data.businessRole()).isEqualTo("SUPER_ADMIN");
        // Immutable business classification is returned on login (not on /me)
        assertThat(data.size()).isEqualTo("Mikro");
        assertThat(data.industry()).isEqualTo("Makanan & Minuman");
        // Bundled app settings so the client can render its home screen without extra round trips
        assertThat(data.accountSettings()).isNotNull();
        assertThat(meterRegistry.counter("login.attempts", "result", "success").count()).isEqualTo(1.0);

        ArgumentCaptor<Session> captor = ArgumentCaptor.forClass(Session.class);
        verify(sessionRepository).save(captor.capture());
        assertThat(captor.getValue().getPushToken()).isEqualTo("fcm-token");
        assertThat(captor.getValue().getDeviceId()).isEqualTo("dev-1");
    }

    @Test
    void reloginWithoutPushTokenKeepsExistingToken() {
        Session existing = new Session();
        existing.setUsername("alice");
        existing.setDeviceId("dev-1");
        existing.setPushToken("existing-fcm-token");

        when(userRepository.findByUsernameAndDeletedAtIsNull("alice")).thenReturn(Optional.of(user("alice")));
        when(passwordEncoder.matches("pw", "$hash")).thenReturn(true);
        when(businessRepository.findById(BIZ)).thenReturn(Optional.of(business()));
        when(businessBankRepository.findByBusinessId(BIZ)).thenReturn(Optional.of(bank()));
        when(sessionRepository.findByUsernameAndDeviceId("alice", "dev-1")).thenReturn(Optional.of(existing));
        when(jwtService.generateToken("alice", "$hash")).thenReturn("jwt-token");

        authService.login(new LoginRequestDto("alice", "pw", "dev-1", null));

        ArgumentCaptor<Session> captor = ArgumentCaptor.forClass(Session.class);
        verify(sessionRepository).save(captor.capture());
        assertThat(captor.getValue().getPushToken()).isEqualTo("existing-fcm-token");
    }

    @Test
    void reloginWithNewPushTokenOverwritesIt() {
        Session existing = new Session();
        existing.setUsername("alice");
        existing.setDeviceId("dev-1");
        existing.setPushToken("old-token");

        when(userRepository.findByUsernameAndDeletedAtIsNull("alice")).thenReturn(Optional.of(user("alice")));
        when(passwordEncoder.matches("pw", "$hash")).thenReturn(true);
        when(businessRepository.findById(BIZ)).thenReturn(Optional.of(business()));
        when(businessBankRepository.findByBusinessId(BIZ)).thenReturn(Optional.of(bank()));
        when(sessionRepository.findByUsernameAndDeviceId("alice", "dev-1")).thenReturn(Optional.of(existing));
        when(jwtService.generateToken("alice", "$hash")).thenReturn("jwt-token");

        authService.login(new LoginRequestDto("alice", "pw", "dev-1", "new-token"));

        assertThat(existing.getPushToken()).isEqualTo("new-token");
    }

    @Test
    void logoutBlacklistsTokenAndClearsDeviceToken() {
        when(jwtService.parse("token")).thenReturn(java.util.Optional.of(new JwtService.TokenClaims(
                "alice", "jti-123", "binding", java.time.LocalDateTime.now().plusDays(1))));

        authService.logout("token", "alice", "dev-1");

        verify(tokenBlacklistService).blacklist(anyString(), any());
        verify(sessionRepository).clearPushTokenAndLogout(eq("alice"), eq("dev-1"), any());
    }

    @Test
    void logoutWithoutDeviceIdOnlyBlacklists() {
        when(jwtService.parse("token")).thenReturn(java.util.Optional.of(new JwtService.TokenClaims(
                "alice", "jti-123", "binding", java.time.LocalDateTime.now().plusDays(1))));

        authService.logout("token", "alice", null);

        verify(tokenBlacklistService).blacklist(anyString(), any());
        verify(sessionRepository, never()).clearPushTokenAndLogout(any(), any(), any());
    }
}
