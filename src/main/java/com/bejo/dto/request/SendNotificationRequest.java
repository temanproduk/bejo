package com.bejo.dto.request;

import jakarta.validation.constraints.NotBlank;

import java.util.Map;

public record SendNotificationRequest(
        @NotBlank(message = "deviceId is required") String deviceId,
        @NotBlank(message = "title is required") String title,
        @NotBlank(message = "body is required") String body,
        Map<String, String> data
) {}
