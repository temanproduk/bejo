package com.bejo.admin.dto;

/** A started dashboard session. {@code expiresIn} is in seconds, like the app login's. */
public record AdminLoginResponseDto(String token, String tokenType, long expiresIn, String email) {}
