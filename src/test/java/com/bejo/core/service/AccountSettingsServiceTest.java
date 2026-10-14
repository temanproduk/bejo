package com.bejo.core.service;

import com.bejo.core.dto.UpdateKeywordsRequestDto;
import com.bejo.core.dto.UpdateMonitoredAppsRequestDto;
import com.bejo.core.dto.AccountSettingsResponseDto;
import com.bejo.core.domain.AccountSettings;
import com.bejo.core.repository.AccountSettingsRepository;
import com.bejo.core.repository.UserRepository;
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
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountSettingsServiceTest {

    private static final Long BIZ = 1L;

    @Mock private AccountSettingsRepository repository;
    @Mock private UserRepository userRepository;
    @Mock private BusinessContext businessContext;

    private AccountSettingsService service;

    @BeforeEach
    void setUp() {
        service = new AccountSettingsService(repository, userRepository, businessContext);
        lenient().when(businessContext.requireBusinessId("alice")).thenReturn(BIZ);           // reads
        lenient().when(businessContext.requireSuperAdminBusinessId("alice")).thenReturn(BIZ);  // mutations (super admin)
        lenient().when(userRepository.countByBusinessIdAndDeletedAtIsNull(BIZ)).thenReturn(1L); // solo by default
    }

    private AccountSettings existing() {
        AccountSettings s = new AccountSettings();
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

        AccountSettingsResponseDto data = service.getSettings("alice");

        assertThat(data.monitoredApps()).isEmpty();
        assertThat(data.includeKeywords()).isEmpty();
        assertThat(data.excludeKeywords()).isEmpty();
        assertThat(data.blacklistEnabled()).isTrue();
        assertThat(data.whitelistEnabled()).isFalse();
        assertThat(data.qrisWithProfilePicture()).isFalse();
        // Product/stock and team management are on for every business, with no row and no flag set
        assertThat(data.inventoryEnabled()).isTrue();
        assertThat(data.teamManagementEnabled()).isTrue();
    }

    @Test
    void teamManagementAndInventoryAreOnWhateverTheStoredFlagsSay() {
        // A row with both stored flags OFF (the pre-2026-10-14 default, or an older app build that toggled them)
        AccountSettings stored = existing();
        stored.setInventoryEnabled(false);
        stored.setTeamManagementEnabled(false);
        when(repository.findByBusinessId(BIZ)).thenReturn(Optional.of(stored));

        AccountSettingsResponseDto data = service.getSettings("alice");

        assertThat(data.inventoryEnabled()).isTrue();
        assertThat(data.teamManagementEnabled()).isTrue();
        assertThat(service.isTeamManagementEnabled(BIZ)).isTrue();
        assertThatCode(() -> service.requireTeamManagementEnabled(BIZ)).doesNotThrowAnyException();   // never refused
        assertThat(stored.isTeamManagementEnabled()).isFalse();   // the stored flag itself is untouched
    }

    @Test
    void toggleRejectsUnknownSetting() {
        assertThatThrownBy(() -> service.toggle("alice", "bogus", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tidak dikenal");
        verify(repository, never()).save(any());
    }

    @Test
    void getReturnsStoredSettings() {
        when(repository.findByBusinessId(BIZ)).thenReturn(Optional.of(existing()));

        AccountSettingsResponseDto data = service.getSettings("alice");

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

        AccountSettingsResponseDto data = service.updateMonitoredApps("alice",
                new UpdateMonitoredAppsRequestDto(apps));

        ArgumentCaptor<AccountSettings> captor = ArgumentCaptor.forClass(AccountSettings.class);
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
        AccountSettings s = existing();
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
        AccountSettings s = existing();
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

        ArgumentCaptor<AccountSettings> captor = ArgumentCaptor.forClass(AccountSettings.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().getBusinessId()).isEqualTo(BIZ);
        assertThat(captor.getValue().getIncludeKeywords()).containsExactly("uang masuk");
    }

    @Test
    void updateQrisWithProfilePictureSavesFlag() {
        when(repository.findByBusinessId(BIZ)).thenReturn(Optional.of(existing()));

        AccountSettingsResponseDto data = service.toggle("alice", "qrisWithProfilePicture", true);

        ArgumentCaptor<AccountSettings> captor = ArgumentCaptor.forClass(AccountSettings.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().isQrisWithProfilePicture()).isTrue();
        assertThat(data.qrisWithProfilePicture()).isTrue();
    }

    @Test
    void nonSuperAdminCannotChangeSettings() {
        // A plain member: reads are fine, but every mutation is 403
        when(businessContext.requireSuperAdminBusinessId("bob"))
                .thenThrow(new com.bejo.shared.exception.ForbiddenException("Hanya super admin yang dapat mengubah pengaturan bisnis"));

        assertThatThrownBy(() -> service.toggle("bob", "inventory", true))
                .isInstanceOf(com.bejo.shared.exception.ForbiddenException.class);
        assertThatThrownBy(() -> service.toggle("bob", "qrisWithProfilePicture", true))
                .isInstanceOf(com.bejo.shared.exception.ForbiddenException.class);
        assertThatThrownBy(() -> service.updateMonitoredApps("bob",
                new UpdateMonitoredAppsRequestDto(Map.of("id.dana", "dana"))))
                .isInstanceOf(com.bejo.shared.exception.ForbiddenException.class);
        assertThatThrownBy(() -> service.toggle("bob", "receiveAds", true))
                .isInstanceOf(com.bejo.shared.exception.ForbiddenException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void updateInventoryEnabledCreatesRowWhenAbsent() {
        when(repository.findByBusinessId(BIZ)).thenReturn(Optional.empty());

        AccountSettingsResponseDto data = service.toggle("alice", "inventory", true);

        ArgumentCaptor<AccountSettings> captor = ArgumentCaptor.forClass(AccountSettings.class);
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

        AccountSettingsResponseDto data = service.toggle("alice", "receiveAds", true);

        ArgumentCaptor<AccountSettings> captor = ArgumentCaptor.forClass(AccountSettings.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().isReceiveAds()).isTrue();
        assertThat(data.receiveAds()).isTrue();
    }
}
