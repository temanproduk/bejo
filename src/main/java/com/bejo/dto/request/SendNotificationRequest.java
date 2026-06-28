package com.bejo.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record SendNotificationRequest(
        @NotBlank(message = "deviceId is required") String deviceId,
        @NotBlank(message = "title is required") String title,
        @NotBlank(message = "body is required") String body,
        @Valid @NotNull(message = "data wajib diisi") NotificationTransactionData data
) {}
