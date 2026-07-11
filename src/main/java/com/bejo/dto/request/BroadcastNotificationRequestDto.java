package com.bejo.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Payload for broadcasting a test FCM push to <b>all</b> of the caller's devices (no
 * {@code deviceId} — it fans out to every session the user has a token for). Backed by
 * {@link com.bejo.service.PushNotificationService#sendToUser}.
 */
public record BroadcastNotificationRequestDto(
        @NotBlank(message = "title is required") String title,
        @NotBlank(message = "body is required") String body,
        @Valid @NotNull(message = "data wajib diisi") NotificationTransactionRequestDto data
) {}
