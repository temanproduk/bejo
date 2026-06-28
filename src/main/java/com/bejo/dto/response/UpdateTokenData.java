package com.bejo.dto.response;

public record UpdateTokenData(
        String deviceId,
        String pushToken
) {}
