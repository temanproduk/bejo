package com.bejo.dto.request;

import jakarta.validation.constraints.NotNull;

/** Toggles whether the business wants to receive voice ads (super-admin-only; default off). */
public record UpdateReceiveAdsRequestDto(
        @NotNull(message = "enabled wajib diisi")
        Boolean enabled
) {}
