package com.bejo.dto.response;

import java.util.List;
import java.util.Map;

/**
 * The user's app settings: the monitored-apps map ({@code packageName -> issuer})
 * and the notification keyword filters. Lists/map may be empty when nothing has been
 * synced yet — the client falls back to its own bundled defaults in that case.
 */
public record AppSettingsResponseDto(
        Map<String, String> monitoredApps,
        List<String> includeKeywords,
        List<String> excludeKeywords,
        boolean blacklistEnabled,
        boolean whitelistEnabled,
        boolean qrisWithProfilePicture,
        boolean inventoryEnabled,
        // Whether the business opted in to receive voice ads (super-admin-set; default false)
        boolean receiveAds
) {}
