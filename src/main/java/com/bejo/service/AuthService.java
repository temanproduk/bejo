package com.bejo.service;

import com.bejo.dto.request.LoginRequestDto;
import com.bejo.dto.response.LoginResponseDto;
import com.bejo.entity.Business;
import com.bejo.entity.BusinessBank;
import com.bejo.entity.Session;
import com.bejo.entity.User;
import com.bejo.exception.ResourceNotFoundException;
import com.bejo.repository.BusinessBankRepository;
import com.bejo.repository.BusinessRepository;
import com.bejo.repository.SessionRepository;
import com.bejo.repository.UserRepository;
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
    private final AppSettingsService appSettingsService;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TokenBlacklistService tokenBlacklistService;
    private final Counter loginSuccessCounter;
    private final Counter loginFailureCounter;

    public AuthService(UserRepository userRepository,
                       BusinessRepository businessRepository,
                       BusinessBankRepository businessBankRepository,
                       SessionRepository sessionRepository,
                       AppSettingsService appSettingsService,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       TokenBlacklistService tokenBlacklistService,
                       MeterRegistry meterRegistry) {
        this.userRepository = userRepository;
        this.businessRepository = businessRepository;
        this.businessBankRepository = businessBankRepository;
        this.sessionRepository = sessionRepository;
        this.appSettingsService = appSettingsService;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.loginSuccessCounter = Counter.builder("login.attempts").tag("result", "success").register(meterRegistry);
        this.loginFailureCounter = Counter.builder("login.attempts").tag("result", "failure").register(meterRegistry);
    }

    @Transactional
    public LoginResponseDto login(LoginRequestDto request) {
        log.info("[LOGIN] username={} deviceId={}", request.username(), request.deviceId());

        User user = userRepository.findByUsernameAndDeletedAtIsNull(request.username())
                .orElseThrow(() -> {
                    loginFailureCounter.increment();
                    return new IllegalArgumentException("Invalid credentials");
                });

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            loginFailureCounter.increment();
            log.warn("[LOGIN] Bad password for username={}", request.username());
            throw new IllegalArgumentException("Invalid credentials");
        }

        Business business = businessRepository.findById(user.getBusinessId())
                .orElseThrow(() -> new ResourceNotFoundException("Business not found"));
        BusinessBank bank = businessBankRepository.findByBusinessId(user.getBusinessId())
                .orElseThrow(() -> new ResourceNotFoundException("Bank data not found"));

        upsertSession(request);

        String token = jwtService.generateToken(request.username());
        var appSettings = appSettingsService.getSettings(request.username());

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
                business.getName(),
                business.getAddress(),
                business.getNmid(),
                bank.getBankName(),
                bank.getBankNumber(),
                bank.getBankAccountName(),
                business.getPicture(),
                appSettings,
                token, "Bearer",
                jwtService.getExpiresInSeconds());
    }

    @Transactional
    public void logout(String token, String username, String deviceId) {
        String jti = jwtService.extractJti(token);
        LocalDateTime expiresAt = jwtService.extractExpiration(token);
        tokenBlacklistService.blacklist(jti, expiresAt);
        log.info("[LOGOUT] Token blacklisted jti={}", jti);

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
