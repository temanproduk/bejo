package com.bejo.dto.request;

import jakarta.validation.constraints.NotBlank;

public record UpdateTokenRequest(
        @NotBlank(message = "pushToken is required") String pushToken
) {}
