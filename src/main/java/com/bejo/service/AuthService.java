package com.bejo.service;

import com.bejo.dto.request.LoginRequest;
import com.bejo.dto.response.LoginData;
import com.bejo.entity.Session;
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
    private final SessionRepository sessionRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final TokenBlacklistService tokenBlacklistService;
    private final Counter loginSuccessCounter;
    private final Counter loginFailureCounter;

    public AuthService(UserRepository userRepository,
                       SessionRepository sessionRepository,
                       PasswordEncoder passwordEncoder,
                       JwtService jwtService,
                       TokenBlacklistService tokenBlacklistService,
                       MeterRegistry meterRegistry) {
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.tokenBlacklistService = tokenBlacklistService;
        this.loginSuccessCounter = Counter.builder("login.attempts").tag("result", "success").register(meterRegistry);
        this.loginFailureCounter = Counter.builder("login.attempts").tag("result", "failure").register(meterRegistry);
    }

    @Transactional
    public LoginData login(LoginRequest request) {
        log.info("[LOGIN] username={} deviceId={}", request.username(), request.deviceId());

        var user = userRepository.findByUsername(request.username())
                .orElseThrow(() -> {
                    loginFailureCounter.increment();
                    return new IllegalArgumentException("Invalid credentials");
                });

        if (!passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            loginFailureCounter.increment();
            log.warn("[LOGIN] Bad password for username={}", request.username());
            throw new IllegalArgumentException("Invalid credentials");
        }

        upsertSession(request);

        String token = jwtService.generateToken(request.username());

        loginSuccessCounter.increment();
        log.info("[LOGIN] Success for username={}", request.username());
        return new LoginData(
                request.username(),
                user.getName(),
                user.getEmail(),
                user.getPhone(),
                user.getBusinessName(),
                user.getBusinessAddress(),
                user.getBankName(),
                user.getBankNumber(),
                user.getBankAccountName(),
                token, "Bearer");
    }

    @Transactional
    public void logout(String token) {
        String jti = jwtService.extractJti(token);
        LocalDateTime expiresAt = jwtService.extractExpiration(token);
        tokenBlacklistService.blacklist(jti, expiresAt);
        log.info("[LOGOUT] Token blacklisted jti={}", jti);
    }

    private void upsertSession(LoginRequest request) {
        sessionRepository.findByUsernameAndDeviceId(request.username(), request.deviceId())
                .ifPresentOrElse(existing -> {
                    existing.setLoginStatus(true);
                    existing.setPushToken(request.pushToken());
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
