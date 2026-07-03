package com.bejo.service;

import com.bejo.dto.request.LoginRequestDto;
import com.bejo.dto.request.RegisterRequestDto;
import com.bejo.dto.response.LoginResponseDto;
import com.bejo.entity.Session;
import com.bejo.entity.User;
import com.bejo.entity.UserBank;
import com.bejo.entity.UserQris;
import com.bejo.repository.SessionRepository;
import com.bejo.repository.UserBankRepository;
import com.bejo.repository.UserQrisRepository;
import com.bejo.repository.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private UserBankRepository userBankRepository;
    @Mock private UserQrisRepository userQrisRepository;
    @Mock private SessionRepository sessionRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtService jwtService;
    @Mock private TokenBlacklistService tokenBlacklistService;

    private SimpleMeterRegistry meterRegistry;
    private AuthService authService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        authService = new AuthService(userRepository, userBankRepository, userQrisRepository,
                sessionRepository, passwordEncoder, jwtService, tokenBlacklistService, meterRegistry);
    }

    private User user(String username) {
        User u = new User();
        u.setUsername(username);
        u.setPasswordHash("$hash");
        u.setName("Alice");
        u.setEmail("alice@example.com");
        u.setPhone("+628111111111");
        u.setBusinessName("Warung Alice");
        u.setAccountType("MANAGED");
        return u;
    }

    private UserBank bank(String username) {
        UserBank b = new UserBank();
        b.setUsername(username);
        b.setBankName("BCA");
        b.setBankNumber("1234567890");
        b.setBankAccountName("Alice");
        return b;
    }

    @Test
    void loginWithUnknownUserFailsAndCountsFailure() {
        when(userRepository.findByUsername("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(new LoginRequestDto("ghost", "pw", "dev-1", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid credentials");

        assertThat(meterRegistry.counter("login.attempts", "result", "failure").count()).isEqualTo(1.0);
    }

    @Test
    void loginWithWrongPasswordFails() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user("alice")));
        when(passwordEncoder.matches("wrong", "$hash")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(new LoginRequestDto("alice", "wrong", "dev-1", null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid credentials");

        assertThat(meterRegistry.counter("login.attempts", "result", "failure").count()).isEqualTo(1.0);
    }

    @Test
    void successfulLoginReturnsTokenAndCreatesSession() {
        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user("alice")));
        when(passwordEncoder.matches("pw", "$hash")).thenReturn(true);
        when(userBankRepository.findByUsername("alice")).thenReturn(Optional.of(bank("alice")));
        when(sessionRepository.findByUsernameAndDeviceId("alice", "dev-1")).thenReturn(Optional.empty());
        when(jwtService.generateToken("alice")).thenReturn("jwt-token");
        when(jwtService.getExpiresInSeconds()).thenReturn(86_400L);

        LoginResponseDto data = authService.login(new LoginRequestDto("alice", "pw", "dev-1", "fcm-token"));

        assertThat(data.token()).isEqualTo("jwt-token");
        assertThat(data.tokenType()).isEqualTo("Bearer");
        assertThat(data.expiresIn()).isEqualTo(86_400L);
        assertThat(data.bankName()).isEqualTo("BCA");
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

        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user("alice")));
        when(passwordEncoder.matches("pw", "$hash")).thenReturn(true);
        when(userBankRepository.findByUsername("alice")).thenReturn(Optional.of(bank("alice")));
        when(sessionRepository.findByUsernameAndDeviceId("alice", "dev-1")).thenReturn(Optional.of(existing));
        when(jwtService.generateToken("alice")).thenReturn("jwt-token");

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

        when(userRepository.findByUsername("alice")).thenReturn(Optional.of(user("alice")));
        when(passwordEncoder.matches("pw", "$hash")).thenReturn(true);
        when(userBankRepository.findByUsername("alice")).thenReturn(Optional.of(bank("alice")));
        when(sessionRepository.findByUsernameAndDeviceId("alice", "dev-1")).thenReturn(Optional.of(existing));
        when(jwtService.generateToken("alice")).thenReturn("jwt-token");

        authService.login(new LoginRequestDto("alice", "pw", "dev-1", "new-token"));

        assertThat(existing.getPushToken()).isEqualTo("new-token");
    }

    @Test
    void registerRejectsDuplicateUsername() {
        when(userRepository.existsByUsername("alice")).thenReturn(true);

        assertThatThrownBy(() -> authService.register(registerRequest()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Username sudah digunakan");

        verify(userRepository, never()).save(any());
    }

    @Test
    void registerCreatesUserBankAndQris() {
        when(userRepository.existsByUsername("alice")).thenReturn(false);
        when(passwordEncoder.encode("password1")).thenReturn("$encoded");

        authService.register(registerRequest());

        ArgumentCaptor<User> userCaptor = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(userCaptor.capture());
        assertThat(userCaptor.getValue().getPasswordHash()).isEqualTo("$encoded");
        assertThat(userCaptor.getValue().getAccountType()).isEqualTo("MANAGED");

        ArgumentCaptor<UserBank> bankCaptor = ArgumentCaptor.forClass(UserBank.class);
        verify(userBankRepository).save(bankCaptor.capture());
        // Bank fields not supplied at registration default to empty strings
        assertThat(bankCaptor.getValue().getBankName()).isEmpty();

        ArgumentCaptor<UserQris> qrisCaptor = ArgumentCaptor.forClass(UserQris.class);
        verify(userQrisRepository).save(qrisCaptor.capture());
        assertThat(qrisCaptor.getValue().getUsername()).isEqualTo("alice");
    }

    @Test
    void logoutBlacklistsTokenJti() {
        when(jwtService.extractJti("token")).thenReturn("jti-123");
        when(jwtService.extractExpiration("token")).thenReturn(java.time.LocalDateTime.now().plusDays(1));

        authService.logout("token");

        verify(tokenBlacklistService).blacklist(anyString(), any());
    }

    private RegisterRequestDto registerRequest() {
        return new RegisterRequestDto("alice", "password1", "Alice", "alice@example.com",
                "+628111111111", "Warung Alice", null, null, null, null, null);
    }
}
