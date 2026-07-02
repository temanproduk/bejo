package com.bejo.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {

    private static final String SECRET =
            "dGVzdC1zZWNyZXQta2V5LWZvci11bml0LXRlc3RzLW11c3QtYmUtMjU2LWJpdHMtbG9uZyE=";
    private static final String OTHER_SECRET =
            "YW5vdGhlci1zZWNyZXQta2V5LXRoYXQtaXMtZGlmZmVyZW50LWFuZC0yNTYtYml0cy1sb25nIQ==";

    private JwtService jwtService;

    @BeforeEach
    void setUp() {
        jwtService = newService(SECRET, 86_400_000L);
    }

    private JwtService newService(String secret, long expirationMs) {
        JwtService service = new JwtService();
        ReflectionTestUtils.setField(service, "secret", secret);
        ReflectionTestUtils.setField(service, "expirationMs", expirationMs);
        return service;
    }

    @Test
    void generatedTokenRoundTripsUsernameAndJti() {
        String token = jwtService.generateToken("alice");

        assertThat(jwtService.isValid(token)).isTrue();
        assertThat(jwtService.extractUsername(token)).isEqualTo("alice");
        assertThat(jwtService.extractJti(token)).isNotBlank();
    }

    @Test
    void eachTokenGetsUniqueJti() {
        String jti1 = jwtService.extractJti(jwtService.generateToken("alice"));
        String jti2 = jwtService.extractJti(jwtService.generateToken("alice"));
        assertThat(jti1).isNotEqualTo(jti2);
    }

    @Test
    void expirationClaimMatchesConfiguredTtl() {
        String token = jwtService.generateToken("alice");

        LocalDateTime expiration = jwtService.extractExpiration(token);
        assertThat(expiration)
                .isAfter(LocalDateTime.now().plusHours(23))
                .isBefore(LocalDateTime.now().plusHours(25));
        assertThat(jwtService.getExpiresInSeconds()).isEqualTo(86_400L);
    }

    @Test
    void expiredTokenIsRejected() throws InterruptedException {
        JwtService shortLived = newService(SECRET, 1L);
        String token = shortLived.generateToken("alice");

        Thread.sleep(50);

        assertThat(shortLived.isValid(token)).isFalse();
    }

    @Test
    void zeroTtlDisablesExpiry() {
        JwtService noExpiry = newService(SECRET, 0L);
        String token = noExpiry.generateToken("alice");

        assertThat(noExpiry.isValid(token)).isTrue();
        assertThat(noExpiry.getExpiresInSeconds()).isNull();
        // Legacy behavior: blacklist entry far in the future
        assertThat(noExpiry.extractExpiration(token)).isAfter(LocalDateTime.now().plusYears(50));
    }

    @Test
    void tokenSignedWithDifferentKeyIsInvalid() {
        String forged = newService(OTHER_SECRET, 86_400_000L).generateToken("alice");

        assertThat(jwtService.isValid(forged)).isFalse();
    }

    @Test
    void garbageTokensAreInvalid() {
        assertThat(jwtService.isValid("not-a-jwt")).isFalse();
        assertThat(jwtService.isValid("")).isFalse();
        assertThat(jwtService.isValid("a.b.c")).isFalse();
    }
}
