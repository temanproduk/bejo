package com.bejo.core.dto;

import jakarta.validation.constraints.NotNull;

import java.util.Map;

/**
 * Replaces the user's monitored-apps map ({@code packageName -> issuer}). Sending
 * an empty map clears the list. Entry-level validation (non-blank keys/values,
 * package-name format, size caps) is enforced in the service.
 */
public record UpdateMonitoredAppsRequestDto(
        @NotNull(message = "apps wajib diisi")
        Map<String, String> apps
) {}
