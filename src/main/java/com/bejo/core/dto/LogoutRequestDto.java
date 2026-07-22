package com.bejo.core.dto;

import jakarta.validation.constraints.Size;

/**
 * Optional logout body. When {@code deviceId} is supplied, the device's FCM token is
 * cleared so it stops receiving pushes; omitting it (or the whole body) still logs out
 * by blacklisting the token.
 */
public record LogoutRequestDto(
        @Size(max = 255, message = "deviceId maksimal 255 karakter")
        String deviceId
) {}
