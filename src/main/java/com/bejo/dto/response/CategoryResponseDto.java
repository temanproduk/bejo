package com.bejo.dto.response;

import java.time.LocalDateTime;

public record CategoryResponseDto(
        Long id,
        String name,
        LocalDateTime createdAt
) {}
