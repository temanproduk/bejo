package com.bejo.core.service;

import com.bejo.core.dto.LoginRequestDto;
import com.bejo.core.dto.LoginResponseDto;
import com.bejo.core.domain.Business;
import com.bejo.core.domain.BusinessBank;
import com.bejo.core.domain.Session;
import com.bejo.core.domain.User;
import com.bejo.shared.exception.ResourceNotFoundException;
import com.bejo.core.repository.BusinessBankRepository;
import com.bejo.core.repository.BusinessRepository;
import com.bejo.core.repository.SessionRepository;
import com.bejo.core.repository.UserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    private final UserRepository userRepository;
    private final BusinessRepository businessRepository;
    private final BusinessBankRepository businessBankRepository;
    private final SessionRepository sessionRepository;
    private final QrisService qrisService;
    private final AccountSettingsService accountSettingsService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TokenBlacklistService tokenBlacklistService;
    private final LoginThrottleService loginThrottleService;
    private final Counter loginSuccessCounter;
    private final Counter loginFailureCounter;

    /**
     * A real BCrypt hash of a value nobody knows, verified against when the username doesn't
     * exist. Without it an unknown username returns before any hashing while a real one pays
     * ~100ms, and that timing difference tells an attacker which usernames are valid.
     */
    private final String dummyHash;

    public AuthService(UserRepository userRepository,
                       BusinessRepository businessRepository,
                       BusinessBankRepository businessBankRepository,
                       SessionRepository sessionRepository,
                       QrisService qrisService,
                       AccountSettingsService accountSettingsService,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       TokenBlacklistService tokenBlacklistService,
                       LoginThrottleService loginThrottleService,
                       MeterRegistry meterRegistry) {
        this.userRepository = userRepository;
        this.businessRepository = businessRepository;
        this.businessBankRepository = businessBankRepository;
        this.sessionRepository = sessionRepository;
        this.qrisService = qrisService;
        this.accountSettingsService = accountSettingsService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.loginThrottleService = loginThrottleService;
        this.dummyHash = passwordEncoder.encode(java.util.UUID.randomUUID().toString());
        this.loginSuccessCounter = Counter.builder("login.attempts").tag("result", "success").register(meterRegistry);
        this.loginFailureCounter = Counter.builder("login.attempts").tag("result", "failure").register(meterRegistry);
    }

    @Transactional
    public LoginResponseDto login(LoginRequestDto request) {
        log.debug("[LOGIN] username={} deviceId={}", request.username(), request.deviceId());

        // Refuse outright while this account is locked out (429), before touching the DB.
        loginThrottleService.assertNotLocked(request.username());

        User user = userRepository.findByUsernameAndDeletedAtIsNull(request.username()).orElse(null);
        // Always run one BCrypt comparison, even for an unknown username, so the response time
        // doesn't reveal whether the account exists.
        String hash = user != null ? user.getPasswordHash() : dummyHash;
        boolean passwordOk = passwordEncoder.matches(request.password(), hash);

        if (user == null || !passwordOk) {
            loginFailureCounter.increment();
            loginThrottleService.recordFailure(request.username());
            log.warn("[LOGIN] Failed attempt for username={}", request.username());
            throw new IllegalArgumentException("Nama pengguna atau kata sandi salah");
        }
        loginThrottleService.recordSuccess(request.username());

        Business business = businessRepository.findById(user.getBusinessId())
                .orElseThrow(() -> new ResourceNotFoundException("Business not found"));
        BusinessBank bank = businessBankRepository.findByBusinessId(user.getBusinessId())
                .orElseThrow(() -> new ResourceNotFoundException("Bank data not found"));

        upsertSession(request);

        // The token is bound to the current password hash, so changing the password revokes it.
        String token = jwtService.generateToken(request.username(), user.getPasswordHash());
        var accountSettings = accountSettingsService.getSettings(request.username());

        loginSuccessCounter.increment();
        log.info("[LOGIN] Success for username={}", request.username());
        return new LoginResponseDto(
                user.getId(),
                request.username(),
                user.getAccountType(),
                user.getName(),
                user.getEmail(),
                user.isEmailVerified(),
                user.getPhone(),
                user.isPhoneVerified(),
                user.getBusinessRole(),
                qrisService.hasOwnQris(user),
                business.getName(),
                business.getAddress(),
                business.getSize(),
                business.getIndustry(),
                business.getStorePhoto(),
                bank.getBankName(),
                bank.getBankNumber(),
                bank.getBankAccountName(),
                business.getPicture(),
                accountSettings,
                token, "Bearer",
                jwtService.getExpiresInSeconds());
    }

    @Transactional
    public void logout(String token, String username, String deviceId) {
        // Single parse; the request already authenticated, so the token is well-formed here.
        jwtService.parse(token).ifPresent(claims -> {
            tokenBlacklistService.blacklist(claims.jti(), claims.expiresAt());
            log.info("[LOGOUT] Token blacklisted jti={}", claims.jti());
        });

        // Clear the FCM token for the device being logged out so it stops receiving
        // pushes (other devices of the same user are untouched). deviceId is optional
        // for backward compatibility with clients that don't send it.
        if (deviceId != null && !deviceId.isBlank()) {
            int cleared = sessionRepository.clearPushTokenAndLogout(username, deviceId, LocalDateTime.now());
            if (cleared > 0) {
                log.info("[LOGOUT] Cleared FCM token for username={} deviceId={}", username, deviceId);
            }
        }
    }

    private void upsertSession(LoginRequestDto request) {
        sessionRepository.findByUsernameAndDeviceId(request.username(), request.deviceId())
                .ifPresentOrElse(existing -> {
                    existing.setLoginStatus(true);
                    // Keep the stored FCM token when the client logs in without one
                    if (request.pushToken() != null && !request.pushToken().isBlank()) {
                        existing.setPushToken(request.pushToken());
                    }
                    existing.setLoginDate(LocalDateTime.now());
                    existing.setLastUpdated(LocalDateTime.now());
                    sessionRepository.save(existing);
                }, () -> {
                    Session session = new Session();
                    session.setUsername(request.username());
                    session.setDeviceId(request.deviceId());
                    session.setLoginStatus(true);
                    session.setPushToken(request.pushToken());
                    session.setLoginDate(LocalDateTime.now());
                    session.setLastUpdated(LocalDateTime.now());
                    session.setScheduleStart(8);
                    session.setScheduleEnd(22);
                    sessionRepository.save(session);
                });
    }
}
