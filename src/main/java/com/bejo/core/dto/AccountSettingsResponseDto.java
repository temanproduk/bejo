package com.bejo.core.dto;

import java.util.List;
import java.util.Map;

/**
 * The account's settings: the monitored-apps map ({@code packageName -> issuer}), the
 * notification keyword filters, and the config toggles. Lists/map may be empty when nothing has
 * been synced yet — the client falls back to its own bundled defaults in that case.
 */
public record AccountSettingsResponseDto(
        Map<String, String> monitoredApps,
        List<String> includeKeywords,
        List<String> excludeKeywords,
        boolean blacklistEnabled,
        boolean whitelistEnabled,
        boolean qrisWithProfilePicture,
        boolean inventoryEnabled,
        // Whether the account opted in to receive voice ads (super-admin-set; default false)
        boolean receiveAds,
        // Whether team management (employees + presence) is active — effective value: the stored
        // flag OR the account already has >1 active user (super-admin-set; default false)
        boolean teamManagementEnabled
) {}
