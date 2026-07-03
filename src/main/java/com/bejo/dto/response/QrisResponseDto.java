package com.bejo.dto.response;

public record QrisResponseDto(
        String type,
        String qrisImage,
        String businessName,
        String businessAddress,
        String nmid
) {}
