package com.bejo.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code PUT /api/account-settings/toggle} — flips one boolean account setting on/off.
 * {@code setting} is one of {@code qrisWithProfilePicture} | {@code inventory} | {@code receiveAds}
 * | {@code teamManagement} (an unknown value is a 400).
 */
public record UpdateSettingToggleRequestDto(
        @NotBlank(message = "setting wajib diisi")
        String setting,

        @NotNull(message = "enabled wajib diisi")
        Boolean enabled
) {}
