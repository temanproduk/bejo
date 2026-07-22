package com.bejo.core.dto;

public record QrisResponseDto(
        String type,
        String qrisImage,
        String businessName,
        String businessAddress,
        String nmid
) {}
