package com.bejo.core.service;

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
    private static final String HASH = "$2a$10$abcdefghijklmnopqrstuv";

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
        String token = jwtService.generateToken("alice", HASH);

        var claims = jwtService.parse(token).orElseThrow();
        assertThat(claims.username()).isEqualTo("alice");
        assertThat(claims.jti()).isNotBlank();
    }

    @Test
    void eachTokenGetsUniqueJti() {
        String jti1 = jwtService.parse(jwtService.generateToken("alice", HASH)).orElseThrow().jti();
        String jti2 = jwtService.parse(jwtService.generateToken("alice", HASH)).orElseThrow().jti();
        assertThat(jti1).isNotEqualTo(jti2);
    }

    @Test
    void expirationClaimMatchesConfiguredTtl() {
        String token = jwtService.generateToken("alice", HASH);

        LocalDateTime expiration = jwtService.parse(token).orElseThrow().expiresAt();
        assertThat(expiration)
                .isAfter(LocalDateTime.now().plusHours(23))
                .isBefore(LocalDateTime.now().plusHours(25));
        assertThat(jwtService.getExpiresInSeconds()).isEqualTo(86_400L);
    }

    @Test
    void expiredTokenIsRejected() throws InterruptedException {
        JwtService shortLived = newService(SECRET, 1L);
        String token = shortLived.generateToken("alice", HASH);

        Thread.sleep(50);

        assertThat(shortLived.parse(token)).isEmpty();
    }

    @Test
    void zeroTtlDisablesExpiry() {
        JwtService noExpiry = newService(SECRET, 0L);
        String token = noExpiry.generateToken("alice", HASH);

        assertThat(noExpiry.parse(token)).isPresent();
        assertThat(noExpiry.getExpiresInSeconds()).isNull();
        // Legacy behavior: blacklist entry far in the future
        assertThat(noExpiry.parse(token).orElseThrow().expiresAt()).isAfter(LocalDateTime.now().plusYears(50));
    }

    @Test
    void tokenSignedWithDifferentKeyIsInvalid() {
        String forged = newService(OTHER_SECRET, 86_400_000L).generateToken("alice", HASH);

        assertThat(jwtService.parse(forged)).isEmpty();
    }

    @Test
    void garbageTokensAreInvalid() {
        assertThat(jwtService.parse("not-a-jwt")).isEmpty();
        assertThat(jwtService.parse("")).isEmpty();
        assertThat(jwtService.parse("a.b.c")).isEmpty();
    }

    // ── Password binding: a token is tied to the hash it was issued against ──

    @Test
    void tokenCarriesBindingMatchingTheIssuingPasswordHash() {
        String token = jwtService.generateToken("alice", HASH);

        assertThat(jwtService.parse(token).orElseThrow().passwordBinding())
                .isEqualTo(jwtService.passwordBinding(HASH));
    }

    @Test
    void bindingChangesWhenThePasswordHashChanges() {
        String tokenBinding = jwtService.parse(jwtService.generateToken("alice", HASH))
                .orElseThrow().passwordBinding();

        // Password changed → new hash → the stored binding no longer matches, so the filter
        // rejects every token issued against the old password.
        assertThat(jwtService.passwordBinding("$2a$10$aDifferentHashEntirely")).isNotEqualTo(tokenBinding);
    }

    @Test
    void bindingIsNotDerivableWithoutTheSigningSecret() {
        // Keyed by the signing secret, so a token holder can't recompute it from the payload
        assertThat(newService(OTHER_SECRET, 86_400_000L).passwordBinding(HASH))
                .isNotEqualTo(jwtService.passwordBinding(HASH));
    }
}
