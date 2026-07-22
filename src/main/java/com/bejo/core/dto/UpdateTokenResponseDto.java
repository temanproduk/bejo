package com.bejo.core.dto;

public record UpdateTokenResponseDto(
        String deviceId,
        String pushToken
) {}
