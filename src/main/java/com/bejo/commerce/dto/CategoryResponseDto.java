package com.bejo.commerce.dto;

import java.time.LocalDateTime;

public record CategoryResponseDto(
        Long id,
        String name,
        LocalDateTime createdAt
) {}
