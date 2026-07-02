package com.bejo.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Date;
import java.util.UUID;

@Service
public class JwtService {

    @Value("${app.jwt.secret}")
    private String secret;

    // 0 or negative disables expiry (tokens live until logout/blacklist)
    @Value("${app.jwt.expiration-ms:7776000000}")
    private long expirationMs;

    public String generateToken(String username) {
        JwtBuilder builder = Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(username)
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

    public String extractUsername(String token) {
        return parseClaims(token).getSubject();
    }

    public String extractJti(String token) {
        return parseClaims(token).getId();
    }

    public LocalDateTime extractExpiration(String token) {
        Date exp = parseClaims(token).getExpiration();
        if (exp == null) {
            // Legacy token without exp — keep the blacklist entry effectively forever
            return LocalDateTime.now().plusYears(100);
        }
        return LocalDateTime.ofInstant(exp.toInstant(), ZoneId.systemDefault());
    }

    public boolean isValid(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(signingKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private SecretKey signingKey() {
        return Keys.hmacShaKeyFor(Decoders.BASE64.decode(secret));
    }
}
