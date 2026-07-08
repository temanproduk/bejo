package com.bejo.entity;

import com.bejo.converter.StringListJsonConverter;
import com.bejo.converter.StringMapJsonConverter;
import jakarta.persistence.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-user app settings synced from the mobile client: the monitored-apps map
 * ({@code packageName -> issuer}) and the notification keyword filters
 * (include/exclude lists + blacklist/whitelist toggles). One row per username.
 */
@Entity
@Table(name = "user_app_settings")
public class UserAppSettings {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    @Convert(converter = StringMapJsonConverter.class)
    @Column(name = "monitored_apps", columnDefinition = "TEXT")
    private Map<String, String> monitoredApps = new LinkedHashMap<>();

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "include_keywords", columnDefinition = "TEXT")
    private List<String> includeKeywords = new ArrayList<>();

    @Convert(converter = StringListJsonConverter.class)
    @Column(name = "exclude_keywords", columnDefinition = "TEXT")
    private List<String> excludeKeywords = new ArrayList<>();

    // Blacklist (exclude keywords) defaults ON; whitelist (include keywords) defaults OFF
    @Column(name = "blacklist_enabled", nullable = false)
    private boolean blacklistEnabled = true;

    @Column(name = "whitelist_enabled", nullable = false)
    private boolean whitelistEnabled = false;

    // App config toggles (default off)
    @Column(name = "qris_with_profile_picture", nullable = false)
    private boolean qrisWithProfilePicture = false;

    @Column(name = "inventory_enabled", nullable = false)
    private boolean inventoryEnabled = false;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public Map<String, String> getMonitoredApps() { return monitoredApps; }
    public void setMonitoredApps(Map<String, String> monitoredApps) { this.monitoredApps = monitoredApps; }
    public List<String> getIncludeKeywords() { return includeKeywords; }
    public void setIncludeKeywords(List<String> includeKeywords) { this.includeKeywords = includeKeywords; }
    public List<String> getExcludeKeywords() { return excludeKeywords; }
    public void setExcludeKeywords(List<String> excludeKeywords) { this.excludeKeywords = excludeKeywords; }
    public boolean isBlacklistEnabled() { return blacklistEnabled; }
    public void setBlacklistEnabled(boolean blacklistEnabled) { this.blacklistEnabled = blacklistEnabled; }
    public boolean isWhitelistEnabled() { return whitelistEnabled; }
    public void setWhitelistEnabled(boolean whitelistEnabled) { this.whitelistEnabled = whitelistEnabled; }
    public boolean isQrisWithProfilePicture() { return qrisWithProfilePicture; }
    public void setQrisWithProfilePicture(boolean qrisWithProfilePicture) { this.qrisWithProfilePicture = qrisWithProfilePicture; }
    public boolean isInventoryEnabled() { return inventoryEnabled; }
    public void setInventoryEnabled(boolean inventoryEnabled) { this.inventoryEnabled = inventoryEnabled; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
