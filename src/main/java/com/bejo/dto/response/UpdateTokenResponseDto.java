package com.bejo.dto.response;

public record UpdateTokenResponseDto(
        String deviceId,
        String pushToken
) {}
