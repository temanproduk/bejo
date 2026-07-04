package com.bejo.service;

import com.bejo.dto.request.UpdateKeywordsRequestDto;
import com.bejo.dto.request.UpdateMonitoredAppsRequestDto;
import com.bejo.dto.response.AppSettingsResponseDto;
import com.bejo.entity.UserAppSettings;
import com.bejo.repository.UserAppSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * Manages a user's app settings: the monitored-apps map and the notification keyword
 * filters (include/exclude lists + blacklist/whitelist toggles). Settings are created
 * lazily on the first update; reads for a user who has never synced return defaults.
 */
@Service
public class AppSettingsService {

    private static final Logger log = LoggerFactory.getLogger(AppSettingsService.class);

    private static final int MAX_APPS = 500;
    private static final int MAX_KEYWORDS = 1000;
    private static final int MAX_PACKAGE_LEN = 255;
    private static final int MAX_ISSUER_LEN = 100;
    private static final int MAX_KEYWORD_LEN = 100;
    // Android package names: letters, digits, underscore, dot
    private static final String PACKAGE_PATTERN = "^[A-Za-z0-9_.]+$";

    private final UserAppSettingsRepository repository;

    public AppSettingsService(UserAppSettingsRepository repository) {
        this.repository = repository;
    }

    @Transactional(readOnly = true)
    public AppSettingsResponseDto getSettings(String username) {
        log.info("[APP-SETTINGS] Get for username={}", username);
        return repository.findByUsername(username)
                .map(this::toResponse)
                // No row yet — return defaults (blacklist ON, whitelist OFF, empty lists)
                .orElseGet(() -> new AppSettingsResponseDto(
                        Map.of(), List.of(), List.of(), true, false));
    }

    @Transactional
    public AppSettingsResponseDto updateMonitoredApps(String username, UpdateMonitoredAppsRequestDto request) {
        Map<String, String> sanitized = sanitizeApps(request.apps());

        UserAppSettings settings = loadOrCreate(username);
        settings.setMonitoredApps(sanitized);
        repository.save(settings);
        log.info("[APP-SETTINGS] Updated monitored apps ({} entries) for username={}", sanitized.size(), username);
        return toResponse(settings);
    }

    @Transactional
    public AppSettingsResponseDto updateKeywords(String username, UpdateKeywordsRequestDto request) {
        if (request.isEmpty()) {
            throw new IllegalArgumentException("Tidak ada field yang diubah");
        }

        UserAppSettings settings = loadOrCreate(username);
        // Partial update — null leaves a field unchanged; an empty list clears it
        if (request.includeKeywords() != null) {
            settings.setIncludeKeywords(sanitizeKeywords(request.includeKeywords(), "includeKeywords"));
        }
        if (request.excludeKeywords() != null) {
            settings.setExcludeKeywords(sanitizeKeywords(request.excludeKeywords(), "excludeKeywords"));
        }
        if (request.blacklistEnabled() != null) {
            settings.setBlacklistEnabled(request.blacklistEnabled());
        }
        if (request.whitelistEnabled() != null) {
            settings.setWhitelistEnabled(request.whitelistEnabled());
        }
        repository.save(settings);
        log.info("[APP-SETTINGS] Updated keywords (include={}, exclude={}, blacklist={}, whitelist={}) for username={}",
                settings.getIncludeKeywords().size(), settings.getExcludeKeywords().size(),
                settings.isBlacklistEnabled(), settings.isWhitelistEnabled(), username);
        return toResponse(settings);
    }

    private UserAppSettings loadOrCreate(String username) {
        return repository.findByUsername(username).orElseGet(() -> {
            UserAppSettings created = new UserAppSettings();
            created.setUsername(username);
            log.info("[APP-SETTINGS] Creating settings row for username={}", username);
            return created;
        });
    }

    private Map<String, String> sanitizeApps(Map<String, String> apps) {
        if (apps.size() > MAX_APPS) {
            throw new IllegalArgumentException("Terlalu banyak aplikasi (maksimal " + MAX_APPS + ")");
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : apps.entrySet()) {
            String pkg = entry.getKey() == null ? "" : entry.getKey().trim();
            String issuer = entry.getValue() == null ? "" : entry.getValue().trim().toLowerCase();
            if (pkg.isEmpty()) {
                throw new IllegalArgumentException("Nama package tidak boleh kosong");
            }
            if (pkg.length() > MAX_PACKAGE_LEN || !pkg.matches(PACKAGE_PATTERN)) {
                throw new IllegalArgumentException("Nama package tidak valid: " + pkg);
            }
            if (issuer.isEmpty()) {
                throw new IllegalArgumentException("Nama issuer tidak boleh kosong untuk package " + pkg);
            }
            if (issuer.length() > MAX_ISSUER_LEN) {
                throw new IllegalArgumentException("Nama issuer terlalu panjang untuk package " + pkg);
            }
            result.put(pkg, issuer);
        }
        return result;
    }

    private List<String> sanitizeKeywords(List<String> keywords, String field) {
        if (keywords.size() > MAX_KEYWORDS) {
            throw new IllegalArgumentException(field + ": terlalu banyak kata kunci (maksimal " + MAX_KEYWORDS + ")");
        }
        // Trim, lowercase, drop blanks, dedupe while preserving order
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        for (String raw : keywords) {
            if (raw == null) continue;
            String kw = raw.trim().toLowerCase();
            if (kw.isEmpty()) continue;
            if (kw.length() > MAX_KEYWORD_LEN) {
                throw new IllegalArgumentException(field + ": kata kunci terlalu panjang: " + kw);
            }
            seen.add(kw);
        }
        return new ArrayList<>(seen);
    }

    private AppSettingsResponseDto toResponse(UserAppSettings s) {
        return new AppSettingsResponseDto(
                new LinkedHashMap<>(s.getMonitoredApps()),
                new ArrayList<>(s.getIncludeKeywords()),
                new ArrayList<>(s.getExcludeKeywords()),
                s.isBlacklistEnabled(),
                s.isWhitelistEnabled());
    }
}
