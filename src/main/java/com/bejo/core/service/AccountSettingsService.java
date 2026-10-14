package com.bejo.core.service;

import com.bejo.core.dto.UpdateKeywordsRequestDto;
import com.bejo.core.dto.UpdateMonitoredAppsRequestDto;
import com.bejo.core.dto.AccountSettingsResponseDto;
import com.bejo.core.domain.AccountSettings;
import com.bejo.shared.exception.ForbiddenException;
import com.bejo.core.repository.AccountSettingsRepository;
import com.bejo.core.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manages an account's settings: the monitored-apps map, the notification keyword filters
 * (include/exclude lists + blacklist/whitelist toggles), and the boolean config toggles
 * (QRIS-on-picture, inventory, receive-ads, team-management). Settings are created lazily on
 * the first update; reads for an account that has never synced return defaults.
 *
 * <p>The boolean toggles are set through one generic {@link #toggle} entry point rather than a
 * method per flag.
 *
 * <p><b>Product/stock management and team management are on for every business</b> (decided 2026-10-14;
 * the app no longer has toggles for them). The stored flags and the toggle endpoint stay — older app builds
 * still send the toggle, and the columns are kept — but they no longer change behaviour: the team features are
 * never refused and both flags are reported as true. Flip {@link #TEAM_MANAGEMENT_ALWAYS_ON} /
 * {@link #INVENTORY_ALWAYS_ON} to bring the per-business flags (and, for team management, the "more than one
 * active user" rule below) back into play.
 *
 * <p>When it is not forced on, {@code teamManagement} is <b>effective</b>: true when explicitly set OR the
 * account already has more than one active user, so an existing team is never locked out.
 */
@Service
public class AccountSettingsService {

    private static final Logger log = LoggerFactory.getLogger(AccountSettingsService.class);

    /** Team management (employee accounts + presence) is always available. See the class doc. */
    static final boolean TEAM_MANAGEMENT_ALWAYS_ON = true;
    /** Product and stock management is always available. See the class doc. */
    static final boolean INVENTORY_ALWAYS_ON = true;

    private static final int MAX_APPS = 500;
    private static final int MAX_KEYWORDS = 1000;
    private static final int MAX_PACKAGE_LEN = 255;
    private static final int MAX_ISSUER_LEN = 100;
    private static final int MAX_KEYWORD_LEN = 100;
    // Android package names: letters, digits, underscore, dot
    private static final String PACKAGE_PATTERN = "^[A-Za-z0-9_.]+$";

    // The generic toggle's accepted setting keys
    private static final String QRIS_WITH_PROFILE_PICTURE = "qrisWithProfilePicture";
    private static final String INVENTORY = "inventory";
    private static final String RECEIVE_ADS = "receiveAds";
    private static final String TEAM_MANAGEMENT = "teamManagement";
    private static final Set<String> TOGGLES = Set.of(QRIS_WITH_PROFILE_PICTURE, INVENTORY, RECEIVE_ADS, TEAM_MANAGEMENT);

    private final AccountSettingsRepository repository;
    private final UserRepository userRepository;
    private final BusinessContext businessContext;

    public AccountSettingsService(AccountSettingsRepository repository, UserRepository userRepository,
                                  BusinessContext businessContext) {
        this.repository = repository;
        this.userRepository = userRepository;
        this.businessContext = businessContext;
    }

    @Transactional(readOnly = true)
    public AccountSettingsResponseDto getSettings(String username) {
        Long businessId = businessContext.requireBusinessId(username);
        log.debug("[ACCOUNT-SETTINGS] Get for businessId={}", businessId);
        boolean teamManagement = teamManagementEffective(businessId, null);
        return repository.findByBusinessId(businessId)
                .map(s -> toResponse(s, teamManagement))
                // No row yet — return defaults (blacklist ON, whitelist OFF, the optional toggles OFF, empty lists;
                // inventory and team management report ON because they are always available)
                .orElseGet(() -> new AccountSettingsResponseDto(
                        Map.of(), List.of(), List.of(), true, false, false, INVENTORY_ALWAYS_ON, false, teamManagement));
    }

    /**
     * Whether team management (employee accounts + presence) is active for a business — the stored
     * flag OR the account already has more than one active user. Used by the team features to gate
     * access, and surfaced on login / {@code /me}.
     */
    @Transactional(readOnly = true)
    public boolean isTeamManagementEnabled(Long businessId) {
        return teamManagementEffective(businessId, null);
    }

    /**
     * Gate for the team features (employee accounts + presence): throws 403 when team management
     * isn't active for the business. Callers invoke this before mutating members or recording
     * attendance/pings.
     */
    @Transactional(readOnly = true)
    public void requireTeamManagementEnabled(Long businessId) {
        if (!teamManagementEffective(businessId, null)) {
            throw new ForbiddenException("Fitur manajemen tim belum diaktifkan");
        }
    }

    @Transactional
    public AccountSettingsResponseDto updateMonitoredApps(String username, UpdateMonitoredAppsRequestDto request) {
        Map<String, String> sanitized = sanitizeApps(request.apps());

        AccountSettings settings = loadOrCreate(username);
        settings.setMonitoredApps(sanitized);
        repository.save(settings);
        log.info("[ACCOUNT-SETTINGS] Updated monitored apps ({} entries) for businessId={}", sanitized.size(), settings.getBusinessId());
        return toResponse(settings, teamManagementEffective(settings.getBusinessId(), settings));
    }

    @Transactional
    public AccountSettingsResponseDto updateKeywords(String username, UpdateKeywordsRequestDto request) {
        if (request.isEmpty()) {
            throw new IllegalArgumentException("Tidak ada data yang diubah");
        }

        AccountSettings settings = loadOrCreate(username);
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
        log.info("[ACCOUNT-SETTINGS] Updated keywords (include={}, exclude={}, blacklist={}, whitelist={}) for businessId={}",
                settings.getIncludeKeywords().size(), settings.getExcludeKeywords().size(),
                settings.isBlacklistEnabled(), settings.isWhitelistEnabled(), settings.getBusinessId());
        return toResponse(settings, teamManagementEffective(settings.getBusinessId(), settings));
    }

    /**
     * Generic on/off for one of the boolean config toggles ({@link #TOGGLES}). Super-admin-only
     * (via {@link #loadOrCreate}); an unknown {@code setting} is a 400.
     */
    @Transactional
    public AccountSettingsResponseDto toggle(String username, String setting, boolean enabled) {
        if (setting == null || !TOGGLES.contains(setting)) {
            throw new IllegalArgumentException("Pengaturan tidak dikenal: " + setting);
        }
        AccountSettings settings = loadOrCreate(username);
        switch (setting) {
            case QRIS_WITH_PROFILE_PICTURE -> settings.setQrisWithProfilePicture(enabled);
            case INVENTORY -> settings.setInventoryEnabled(enabled);
            case RECEIVE_ADS -> settings.setReceiveAds(enabled);
            case TEAM_MANAGEMENT -> settings.setTeamManagementEnabled(enabled);
            default -> throw new IllegalArgumentException("Pengaturan tidak dikenal: " + setting);
        }
        repository.save(settings);
        log.info("[ACCOUNT-SETTINGS] {}={} for businessId={}", setting, enabled, settings.getBusinessId());
        return toResponse(settings, teamManagementEffective(settings.getBusinessId(), settings));
    }

    // Every mutation flows through here, so this is where super-admin is enforced:
    // only the business's super admin may change app config; members can still GET it.
    private AccountSettings loadOrCreate(String username) {
        Long businessId = businessContext.requireSuperAdminBusinessId(username);
        return repository.findByBusinessId(businessId).orElseGet(() -> {
            AccountSettings created = new AccountSettings();
            created.setBusinessId(businessId);
            log.info("[ACCOUNT-SETTINGS] Creating settings row for businessId={}", businessId);
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

    // teamManagement is the effective value (stored flag OR >1 active user) — see the class doc.
    // Pass the already-loaded settings when available to avoid re-reading it.
    private boolean teamManagementEffective(Long businessId, AccountSettings loaded) {
        if (TEAM_MANAGEMENT_ALWAYS_ON) return true;   // no per-business flag, no database read
        boolean stored = loaded != null
                ? loaded.isTeamManagementEnabled()
                : repository.findByBusinessId(businessId).map(AccountSettings::isTeamManagementEnabled).orElse(false);
        return stored || userRepository.countByBusinessIdAndDeletedAtIsNull(businessId) > 1;
    }

    private AccountSettingsResponseDto toResponse(AccountSettings s, boolean teamManagementEnabled) {
        return new AccountSettingsResponseDto(
                new LinkedHashMap<>(s.getMonitoredApps()),
                new ArrayList<>(s.getIncludeKeywords()),
                new ArrayList<>(s.getExcludeKeywords()),
                s.isBlacklistEnabled(),
                s.isWhitelistEnabled(),
                s.isQrisWithProfilePicture(),
                INVENTORY_ALWAYS_ON || s.isInventoryEnabled(),
                s.isReceiveAds(),
                teamManagementEnabled);
    }
}
