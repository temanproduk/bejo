package com.bejo.core.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;

/**
 * Issues and verifies the API's JWTs.
 *
 * <p>A token carries, besides the subject (username) and {@code jti}, a <b>password binding</b>
 * claim ({@value #CLAIM_PASSWORD_BINDING}): a keyed fingerprint of the password hash the token was
 * issued against. {@link JwtAuthFilter} re-derives it from the user's current hash on every
 * request, so <b>changing a password immediately invalidates every token issued before it</b> —
 * on all devices — without needing a per-user revocation column. The fingerprint is an HMAC under
 * the signing key rather than a plain digest, so a token holder can't derive it (or anything about
 * the password) from the payload.
 *
 * <p>Verification is a <b>single</b> parse: {@link #parse(String)} validates the signature and
 * expiry once and hands back every claim the caller needs, instead of re-parsing per field.
 */
@Service
public class JwtService {

    static final String CLAIM_PASSWORD_BINDING = "pwb";

    @Value("${app.jwt.secret}")
    private String secret;

    // 0 or negative disables expiry (tokens live until logout/blacklist)
    @Value("${app.jwt.expiration-ms:604800000}")
    private long expirationMs;

    // Derived once from the configured secret — deriving it per parse was pure overhead.
    // Lazy (not @PostConstruct) so the field-injected secret is set first.
    private volatile SecretKey signingKey;

    /** The verified contents of a token. */
    public record TokenClaims(String username, String jti, String passwordBinding, LocalDateTime expiresAt) {}

    public String generateToken(String username, String passwordHash) {
        JwtBuilder builder = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(username)
                .claim(CLAIM_PASSWORD_BINDING, passwordBinding(passwordHash))
                .issuedAt(new Date())
                .signWith(signingKey());
        if (expirationMs > 0) {
            builder.expiration(new Date(System.currentTimeMillis() + expirationMs));
        }
        return builder.compact();
    }

    public Long getExpiresInSeconds() {
        return expirationMs > 0 ? expirationMs / 1000 : null;
    }

    /**
     * Verifies signature + expiry and returns the claims, or {@link Optional#empty()} if the
     * token is invalid, expired, or malformed. This is the only parse a request needs.
     */
    public Optional<TokenClaims> parse(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            return Optional.of(new TokenClaims(
                    claims.getSubject(),
                    claims.getId(),
                    claims.get(CLAIM_PASSWORD_BINDING, String.class),
                    toLocalDateTime(claims.getExpiration())));
        } catch (JwtException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    /**
     * The keyed fingerprint of a password hash, as embedded in a token. Recomputed from the
     * user's current hash on each request to detect a password change.
     */
    public String passwordBinding(String passwordHash) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(signingKey());
            byte[] digest = mac.doFinal(passwordHash.getBytes(StandardCharsets.UTF_8));
            // 16 hex chars (64 bits) — ample to detect a changed hash, and it reveals nothing
            // about the password since it's keyed by the server-side signing secret.
            return HexFormat.of().formatHex(digest).substring(0, 16);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot derive password binding", e);
        }
    }

    private LocalDateTime toLocalDateTime(Date exp) {
        if (exp == null) {
            // Expiry disabled (expiration-ms <= 0): keep a blacklist entry effectively forever
            return LocalDateTime.now().plusYears(100);
        }
        return LocalDateTime.ofInstant(exp.toInstant(), ZoneId.systemDefault());
    }

    private SecretKey signingKey() {
        SecretKey key = signingKey;
        if (key == null) {
            synchronized (this) {
                key = signingKey;
                if (key == null) {
                    key = Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
                    signingKey = key;
                }
            }
        }
        return key;
    }
}
