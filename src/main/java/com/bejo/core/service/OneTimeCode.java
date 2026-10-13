package com.bejo.core.service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.HexFormat;

/**
 * The emailed 6-digit codes (password reset, email verification): how one is made and how it is stored.
 *
 * <p>A code is never stored, only {@code HMAC-SHA256(purpose + ":" + server secret, subject + ":" + code)}.
 * Six digits are only a million values, so a plain hash would be reversible from a database leak; keyed
 * with the server secret it is not. The {@code purpose} separates the keys, so a verification code can
 * never satisfy a password reset (or the other way round) even for the same user and digits. The
 * {@code subject} is what the code is about: a user id for a reset, a user id plus the address for a
 * verification, so a code sent to one address cannot confirm another.
 */
final class OneTimeCode {

    private static final SecureRandom RANDOM = new SecureRandom();

    private OneTimeCode() {}

    /** A fresh code: six digits, zero-padded. */
    static String generate() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    static String hash(String purpose, String secret, String subject, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec((purpose + ":" + secret).getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((subject + ":" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot hash one-time code", e);
        }
    }
}
