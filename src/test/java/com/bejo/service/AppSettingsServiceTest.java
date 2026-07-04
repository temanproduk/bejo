package com.bejo.service;

import com.bejo.dto.request.UpdateKeywordsRequestDto;
import com.bejo.dto.request.UpdateMonitoredAppsRequestDto;
import com.bejo.dto.response.AppSettingsResponseDto;
import com.bejo.entity.UserAppSettings;
import com.bejo.repository.UserAppSettingsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppSettingsServiceTest {

    @Mock private UserAppSettingsRepository repository;

    private AppSettingsService service;

    @BeforeEach
    void setUp() {
        service = new AppSettingsService(repository);
    }

    private UserAppSettings existing() {
        UserAppSettings s = new UserAppSettings();
        s.setUsername("alice");
        s.setMonitoredApps(new LinkedHashMap<>(Map.of("id.dana", "dana")));
        s.setIncludeKeywords(List.of("uang masuk"));
        s.setExcludeKeywords(List.of("promo"));
        s.setBlacklistEnabled(true);
        s.setWhitelistEnabled(false);
        return s;
    }

    @Test
    void getReturnsDefaultsWhenNoRow() {
        when(repository.findByUsername("alice")).thenReturn(Optional.empty());

        AppSettingsResponseDto data = service.getSettings("alice");

        assertThat(data.monitoredApps()).isEmpty();
        assertThat(data.includeKeywords()).isEmpty();
        assertThat(data.excludeKeywords()).isEmpty();
        assertThat(data.blacklistEnabled()).isTrue();
        assertThat(data.whitelistEnabled()).isFalse();
    }

    @Test
    void getReturnsStoredSettings() {
        when(repository.findByUsername("alice")).thenReturn(Optional.of(existing()));

        AppSettingsResponseDto data = service.getSettings("alice");

        assertThat(data.monitoredApps()).containsEntry("id.dana", "dana");
        assertThat(data.includeKeywords()).containsExactly("uang masuk");
        assertThat(data.excludeKeywords()).containsExactly("promo");
    }

    @Test
    void updateMonitoredAppsSanitizesAndSaves() {
        when(repository.findByUsername("alice")).thenReturn(Optional.empty());
        Map<String, String> apps = new LinkedHashMap<>();
        apps.put("com.gojek.app", "  GoPay  ");   // trimmed + lowercased
        apps.put("id.dana", "dana");

        AppSettingsResponseDto data = service.updateMonitoredApps("alice",
                new UpdateMonitoredAppsRequestDto(apps));

        ArgumentCaptor<UserAppSettings> captor = ArgumentCaptor.forClass(UserAppSettings.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getUsername()).isEqualTo("alice");
        assertThat(captor.getValue().getMonitoredApps())
                .containsEntry("com.gojek.app", "gopay")
                .containsEntry("id.dana", "dana");
        assertThat(data.monitoredApps()).containsEntry("com.gojek.app", "gopay");
    }

    @Test
    void updateMonitoredAppsRejectsInvalidPackage() {
        // Validation runs before any repository lookup, so no stubbing is needed
        Map<String, String> apps = Map.of("not a package!", "issuer");

        assertThatThrownBy(() -> service.updateMonitoredApps("alice", new UpdateMonitoredAppsRequestDto(apps)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("package");
        verify(repository, never()).save(any());
    }

    @Test
    void updateMonitoredAppsRejectsBlankIssuer() {
        Map<String, String> apps = new LinkedHashMap<>();
        apps.put("id.dana", "   ");

        assertThatThrownBy(() -> service.updateMonitoredApps("alice", new UpdateMonitoredAppsRequestDto(apps)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("issuer");
        verify(repository, never()).save(any());
    }

    @Test
    void updateKeywordsPartialUpdateLeavesOtherFields() {
        UserAppSettings s = existing();
        when(repository.findByUsername("alice")).thenReturn(Optional.of(s));

        // Only excludeKeywords supplied — include list and toggles stay put
        service.updateKeywords("alice", new UpdateKeywordsRequestDto(
                null, List.of("Promo", "promo", "diskon", "  "), null, null));

        verify(repository).save(s);
        // Deduped, trimmed, lowercased, blanks dropped
        assertThat(s.getExcludeKeywords()).containsExactly("promo", "diskon");
        assertThat(s.getIncludeKeywords()).containsExactly("uang masuk"); // unchanged
        assertThat(s.isBlacklistEnabled()).isTrue();
    }

    @Test
    void updateKeywordsAppliesTogglesAndClearsWithEmptyList() {
        UserAppSettings s = existing();
        when(repository.findByUsername("alice")).thenReturn(Optional.of(s));

        service.updateKeywords("alice", new UpdateKeywordsRequestDto(
                List.of(), null, false, true));

        assertThat(s.getIncludeKeywords()).isEmpty(); // empty list clears
        assertThat(s.getExcludeKeywords()).containsExactly("promo"); // null leaves unchanged
        assertThat(s.isBlacklistEnabled()).isFalse();
        assertThat(s.isWhitelistEnabled()).isTrue();
    }

    @Test
    void updateKeywordsEmptyRequestRejected() {
        assertThatThrownBy(() -> service.updateKeywords("alice",
                new UpdateKeywordsRequestDto(null, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tidak ada field");
        verify(repository, never()).save(any());
    }

    @Test
    void updateKeywordsCreatesRowWhenAbsent() {
        when(repository.findByUsername("alice")).thenReturn(Optional.empty());

        service.updateKeywords("alice", new UpdateKeywordsRequestDto(
                List.of("uang masuk"), null, null, null));

        ArgumentCaptor<UserAppSettings> captor = ArgumentCaptor.forClass(UserAppSettings.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getUsername()).isEqualTo("alice");
        assertThat(captor.getValue().getIncludeKeywords()).containsExactly("uang masuk");
    }
}
