package com.bejo.service;

import com.bejo.dto.request.UpdateKeywordsRequestDto;
import com.bejo.dto.request.UpdateMonitoredAppsRequestDto;
import com.bejo.dto.response.AppSettingsResponseDto;
import com.bejo.entity.BusinessAppSettings;
import com.bejo.repository.BusinessAppSettingsRepository;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AppSettingsServiceTest {

    private static final Long BIZ = 1L;

    @Mock private BusinessAppSettingsRepository repository;
    @Mock private BusinessContext businessContext;

    private AppSettingsService service;

    @BeforeEach
    void setUp() {
        service = new AppSettingsService(repository, businessContext);
        lenient().when(businessContext.requireBusinessId("alice")).thenReturn(BIZ);           // reads
        lenient().when(businessContext.requireSuperAdminBusinessId("alice")).thenReturn(BIZ);  // mutations (super admin)
    }

    private BusinessAppSettings existing() {
        BusinessAppSettings s = new BusinessAppSettings();
        s.setBusinessId(BIZ);
        s.setMonitoredApps(new LinkedHashMap<>(Map.of("id.dana", "dana")));
        s.setIncludeKeywords(List.of("uang masuk"));
        s.setExcludeKeywords(List.of("promo"));
        s.setBlacklistEnabled(true);
        s.setWhitelistEnabled(false);
        return s;
    }

    @Test
    void getReturnsDefaultsWhenNoRow() {
        when(repository.findByBusinessId(BIZ)).thenReturn(Optional.empty());

        AppSettingsResponseDto data = service.getSettings("alice");

        assertThat(data.monitoredApps()).isEmpty();
        assertThat(data.includeKeywords()).isEmpty();
        assertThat(data.excludeKeywords()).isEmpty();
        assertThat(data.blacklistEnabled()).isTrue();
        assertThat(data.whitelistEnabled()).isFalse();
        assertThat(data.qrisWithProfilePicture()).isFalse();
        assertThat(data.inventoryEnabled()).isFalse();
    }

    @Test
    void getReturnsStoredSettings() {
        when(repository.findByBusinessId(BIZ)).thenReturn(Optional.of(existing()));

        AppSettingsResponseDto data = service.getSettings("alice");

        assertThat(data.monitoredApps()).containsEntry("id.dana", "dana");
        assertThat(data.includeKeywords()).containsExactly("uang masuk");
        assertThat(data.excludeKeywords()).containsExactly("promo");
    }

    @Test
    void updateMonitoredAppsSanitizesAndSaves() {
        when(repository.findByBusinessId(BIZ)).thenReturn(Optional.empty());
        Map<String, String> apps = new LinkedHashMap<>();
        apps.put("com.gojek.app", "  GoPay  ");   // trimmed + lowercased
        apps.put("id.dana", "dana");

        AppSettingsResponseDto data = service.updateMonitoredApps("alice",
                new UpdateMonitoredAppsRequestDto(apps));

        ArgumentCaptor<BusinessAppSettings> captor = ArgumentCaptor.forClass(BusinessAppSettings.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getBusinessId()).isEqualTo(BIZ);
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
        BusinessAppSettings s = existing();
        when(repository.findByBusinessId(BIZ)).thenReturn(Optional.of(s));

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
        BusinessAppSettings s = existing();
        when(repository.findByBusinessId(BIZ)).thenReturn(Optional.of(s));

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
                .hasMessageContaining("Tidak ada data");
        verify(repository, never()).save(any());
    }

    @Test
    void updateKeywordsCreatesRowWhenAbsent() {
        when(repository.findByBusinessId(BIZ)).thenReturn(Optional.empty());

        service.updateKeywords("alice", new UpdateKeywordsRequestDto(
                List.of("uang masuk"), null, null, null));

        ArgumentCaptor<BusinessAppSettings> captor = ArgumentCaptor.forClass(BusinessAppSettings.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getBusinessId()).isEqualTo(BIZ);
        assertThat(captor.getValue().getIncludeKeywords()).containsExactly("uang masuk");
    }

    @Test
    void updateQrisWithProfilePictureSavesFlag() {
        when(repository.findByBusinessId(BIZ)).thenReturn(Optional.of(existing()));

        AppSettingsResponseDto data = service.updateQrisWithProfilePicture("alice", true);

        ArgumentCaptor<BusinessAppSettings> captor = ArgumentCaptor.forClass(BusinessAppSettings.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().isQrisWithProfilePicture()).isTrue();
        assertThat(data.qrisWithProfilePicture()).isTrue();
    }

    @Test
    void nonSuperAdminCannotChangeSettings() {
        // A plain member: reads are fine, but every mutation is 403
        when(businessContext.requireSuperAdminBusinessId("bob"))
                .thenThrow(new com.bejo.exception.ForbiddenException("Hanya super admin yang dapat mengubah pengaturan bisnis"));

        assertThatThrownBy(() -> service.updateInventoryEnabled("bob", true))
                .isInstanceOf(com.bejo.exception.ForbiddenException.class);
        assertThatThrownBy(() -> service.updateQrisWithProfilePicture("bob", true))
                .isInstanceOf(com.bejo.exception.ForbiddenException.class);
        assertThatThrownBy(() -> service.updateMonitoredApps("bob",
                new UpdateMonitoredAppsRequestDto(Map.of("id.dana", "dana"))))
                .isInstanceOf(com.bejo.exception.ForbiddenException.class);
        assertThatThrownBy(() -> service.updateReceiveAds("bob", true))
                .isInstanceOf(com.bejo.exception.ForbiddenException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void updateInventoryEnabledCreatesRowWhenAbsent() {
        when(repository.findByBusinessId(BIZ)).thenReturn(Optional.empty());

        AppSettingsResponseDto data = service.updateInventoryEnabled("alice", true);

        ArgumentCaptor<BusinessAppSettings> captor = ArgumentCaptor.forClass(BusinessAppSettings.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getBusinessId()).isEqualTo(BIZ);
        assertThat(captor.getValue().isInventoryEnabled()).isTrue();
        assertThat(data.inventoryEnabled()).isTrue();
    }

    @Test
    void updateReceiveAdsSavesFlagAndDefaultsFalse() {
        // Default is false in the "never synced" response
        when(repository.findByBusinessId(BIZ)).thenReturn(Optional.empty());
        assertThat(service.getSettings("alice").receiveAds()).isFalse();

        AppSettingsResponseDto data = service.updateReceiveAds("alice", true);

        ArgumentCaptor<BusinessAppSettings> captor = ArgumentCaptor.forClass(BusinessAppSettings.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().isReceiveAds()).isTrue();
        assertThat(data.receiveAds()).isTrue();
    }
}
