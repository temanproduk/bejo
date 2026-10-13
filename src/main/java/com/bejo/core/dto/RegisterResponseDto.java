package com.bejo.core.dto;

/**
 * What registration tells the client: the account exists but cannot log in until its email is verified.
 * {@code email} is masked ("b***@example.com"); {@code emailSent} is false when the code could not be
 * delivered right now, in which case the client offers "resend".
 */
public record RegisterResponseDto(
        boolean emailVerificationRequired,
        boolean emailSent,
        String email
) {}
