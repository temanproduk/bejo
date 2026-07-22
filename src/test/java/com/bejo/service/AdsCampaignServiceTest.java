package com.bejo.service;

import com.bejo.constant.AdCampaignState;
import com.bejo.dto.request.AdDownloadReportRequestDto;
import com.bejo.dto.request.AdLocationReportRequestDto;
import com.bejo.dto.request.AdPlayedReportRequestDto;
import com.bejo.dto.request.CreateAdCampaignRequestDto;
import com.bejo.entity.AdCampaign;
import com.bejo.entity.AdDelivery;
import com.bejo.entity.Session;
import com.bejo.entity.User;
import com.bejo.exception.ForbiddenException;
import com.bejo.repository.AdCampaignRepository;
import com.bejo.repository.AdDeliveryRepository;
import com.bejo.repository.SessionRepository;
import com.bejo.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AdsCampaignServiceTest {

    private static final String USER = "warung";
    private static final String DEVICE = "device-1";
    private static final Long DELIVERY_ID = 10L;
    private static final Long CAMPAIGN_ID = 5L;
    private static final Long BIZ = 1L;
    private static final Long USER_ID = 42L;
    private static final String USER_NAME = "Budi";

    @Mock private AdCampaignRepository campaignRepository;
    @Mock private AdDeliveryRepository deliveryRepository;
    @Mock private UserRepository userRepository;
    @Mock private SessionRepository sessionRepository;
    @Mock private AdsAudioStorage audioStorage;
    @Mock private AdsAudioInspector audioInspector;
    @Mock private PushNotificationService pushNotificationService;
    @Mock private BusinessContext businessContext;

    private AdsCampaignService service;

    @BeforeEach
    void setUp() {
        service = new AdsCampaignService(campaignRepository, deliveryRepository, userRepository,
                sessionRepository, audioStorage, audioInspector, pushNotificationService, businessContext);
        ReflectionTestUtils.setField(service, "signedUrlTtlSeconds", 3600L);
        ReflectionTestUtils.setField(service, "maxAudioBytes", 2_097_152L);
        ReflectionTestUtils.setField(service, "maxAudioSeconds", 10);
    }

    private CreateAdCampaignRequestDto createRequest(String contentType, byte[] audio) {
        return new CreateAdCampaignRequestDto("Promo", "Brand",
                Base64.getEncoder().encodeToString(audio), contentType, USER, DEVICE);
    }

    private Session sessionWithToken() {
        Session s = new Session();
        s.setPushToken("fcm-token");
        return s;
    }

    private AdDelivery delivery(String state) {
        AdDelivery d = new AdDelivery();
        ReflectionTestUtils.setField(d, "id", DELIVERY_ID);
        d.setCampaignId(CAMPAIGN_ID);
        d.setBusinessId(BIZ);
        d.setTargetUserId(USER_ID);
        d.setTargetUsername(USER);
        d.setTargetName(USER_NAME);
        d.setTargetDeviceId(DEVICE);
        d.setState(state);
        return d;
    }

    private User targetUser() {
        User u = new User();
        ReflectionTestUtils.setField(u, "id", USER_ID);
        u.setUsername(USER);
        u.setName(USER_NAME);
        u.setBusinessId(BIZ);
        return u;
    }

    private AdCampaign campaign(String sha, long size) {
        AdCampaign c = new AdCampaign();
        ReflectionTestUtils.setField(c, "id", CAMPAIGN_ID);
        c.setName("Promo");
        c.setAudioObject("campaigns/x.mp3");
        c.setAudioContentType("audio/mpeg");
        c.setAudioSha256(sha);
        c.setAudioSizeBytes(size);
        return c;
    }

    // ---------- create ----------

    @Test
    void createUploadsAudioAndSavesCampaignAndDelivery() {
        when(userRepository.findByUsernameAndDeletedAtIsNull(USER)).thenReturn(Optional.of(targetUser()));
        when(sessionRepository.findByUsernameAndDeviceId(USER, DEVICE)).thenReturn(Optional.of(sessionWithToken()));
        when(audioInspector.durationSeconds(any(), eq("mp3"))).thenReturn(5);

        var dto = service.create(createRequest("audio/mpeg", "hello".getBytes()));

        assertThat(dto.state()).isEqualTo(AdCampaignState.CREATED);
        assertThat(dto.audioSizeBytes()).isEqualTo(5L);
        verify(audioStorage).put(any(), any(), eq("audio/mpeg"));
        verify(campaignRepository).save(any(AdCampaign.class));
        // The delivery snapshots the target (business scope + who it went to — survives removal)
        ArgumentCaptor<AdDelivery> saved = ArgumentCaptor.forClass(AdDelivery.class);
        verify(deliveryRepository).save(saved.capture());
        assertThat(saved.getValue().getBusinessId()).isEqualTo(BIZ);
        assertThat(saved.getValue().getTargetUserId()).isEqualTo(USER_ID);
        assertThat(saved.getValue().getTargetName()).isEqualTo(USER_NAME);
    }

    @Test
    void createRejectsNonAudioContentType() {
        assertThatThrownBy(() -> service.create(createRequest("image/png", "x".getBytes())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tipe audio");
        verify(audioStorage, never()).put(any(), any(), any());
    }

    @Test
    void createRejectsAudioOverTheSizeCap() {
        // The size check runs before the target lookups, so no repo stubs are needed here.
        ReflectionTestUtils.setField(service, "maxAudioBytes", 4L);

        assertThatThrownBy(() -> service.create(createRequest("audio/mpeg", "toolong".getBytes())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maksimal");
        verify(audioStorage, never()).put(any(), any(), any());
    }

    @Test
    void createRejectsUnknownTargetUser() {
        when(userRepository.findByUsernameAndDeletedAtIsNull(USER)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.create(createRequest("audio/mpeg", "x".getBytes())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Target pengguna");
        verify(audioStorage, never()).put(any(), any(), any());
    }

    @Test
    void createRejectsAudioLongerThanTheDurationCap() {
        when(userRepository.findByUsernameAndDeletedAtIsNull(USER)).thenReturn(Optional.of(targetUser()));
        when(sessionRepository.findByUsernameAndDeviceId(USER, DEVICE)).thenReturn(Optional.of(sessionWithToken()));
        when(audioInspector.durationSeconds(any(), eq("mp3"))).thenReturn(15);

        assertThatThrownBy(() -> service.create(createRequest("audio/mpeg", "hello".getBytes())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Durasi audio maksimal");
        verify(audioStorage, never()).put(any(), any(), any());
    }

    @Test
    void createRejectsDeviceWithoutPushToken() {
        when(userRepository.findByUsernameAndDeletedAtIsNull(USER)).thenReturn(Optional.of(targetUser()));
        when(sessionRepository.findByUsernameAndDeviceId(USER, DEVICE)).thenReturn(Optional.of(new Session())); // no token

        assertThatThrownBy(() -> service.create(createRequest("audio/mpeg", "hello".getBytes())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("belum terdaftar notifikasi");
        verify(audioStorage, never()).put(any(), any(), any());
    }

    @Test
    void createRejectsUnsupportedContentType() {
        assertThatThrownBy(() -> service.create(createRequest("audio/webm", "x".getBytes())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tipe audio");
        verify(audioStorage, never()).put(any(), any(), any());
    }

    // ---------- prepare / play ----------

    @Test
    void prepareTransitionsToPreparingAndSignsAUrl() {
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign("abc", 5)));
        when(deliveryRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(List.of(delivery(AdCampaignState.CREATED)));
        when(audioStorage.signedDownloadUrl(any(), any())).thenReturn("https://signed");

        var dto = service.prepare(CAMPAIGN_ID);

        assertThat(dto.state()).isEqualTo(AdCampaignState.PREPARING);
        verify(audioStorage).signedDownloadUrl(eq("campaigns/x.mp3"), any());
        verify(deliveryRepository).save(any(AdDelivery.class));
    }

    @Test
    void prepareRejectsWhenNoDeliveryIsPreparable() {
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign("abc", 5)));
        when(deliveryRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(List.of(delivery(AdCampaignState.FINISHED)));

        assertThatThrownBy(() -> service.prepare(CAMPAIGN_ID))
                .isInstanceOf(IllegalArgumentException.class);
        verify(audioStorage, never()).signedDownloadUrl(any(), any());
    }

    @Test
    void playRejectsWhenNotReady() {
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign("abc", 5)));
        when(deliveryRepository.findByCampaignId(CAMPAIGN_ID)).thenReturn(List.of(delivery(AdCampaignState.PREPARING)));

        assertThatThrownBy(() -> service.play(CAMPAIGN_ID))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ---------- device reports ----------

    @Test
    void downloadedWithMatchingHashMarksReady() {
        when(deliveryRepository.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery(AdCampaignState.PREPARING)));
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign("ABC", 5)));
        when(deliveryRepository.markReady(eq(DELIVERY_ID), any(), eq("abc"), eq(5L))).thenReturn(1);

        service.reportDownloaded(USER, DELIVERY_ID, new AdDownloadReportRequestDto(true, "abc", 5L, null));

        verify(deliveryRepository).markReady(eq(DELIVERY_ID), any(), eq("abc"), eq(5L));
        verify(deliveryRepository, never()).markError(anyLong(), any(), any(), any(), any());
    }

    @Test
    void downloadedWithMismatchedHashMarksError() {
        when(deliveryRepository.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery(AdCampaignState.PREPARING)));
        when(campaignRepository.findById(CAMPAIGN_ID)).thenReturn(Optional.of(campaign("abc", 5)));
        when(deliveryRepository.markError(eq(DELIVERY_ID), any(), any(), any(), any())).thenReturn(1);

        service.reportDownloaded(USER, DELIVERY_ID, new AdDownloadReportRequestDto(true, "wrong", 999L, null));

        verify(deliveryRepository).markError(eq(DELIVERY_ID), any(), any(), eq("wrong"), eq(999L));
        verify(deliveryRepository, never()).markReady(anyLong(), any(), any(), any());
    }

    @Test
    void playedWithLocationFinishesInOneStep() {
        when(deliveryRepository.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery(AdCampaignState.STARTING)));
        when(deliveryRepository.markPlayedAndFinished(eq(DELIVERY_ID), any(), any(), eq(-6.2), eq(106.8))).thenReturn(1);

        service.reportPlayed(USER, DELIVERY_ID,
                new AdPlayedReportRequestDto(LocalDateTime.now(), -6.2, 106.8));

        verify(deliveryRepository).markPlayedAndFinished(eq(DELIVERY_ID), any(), any(), eq(-6.2), eq(106.8));
        verify(deliveryRepository, never()).markPlayed(anyLong(), any());
    }

    @Test
    void playedWithoutLocationOnlyMarksPlayed() {
        when(deliveryRepository.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery(AdCampaignState.STARTING)));
        when(deliveryRepository.markPlayed(eq(DELIVERY_ID), any())).thenReturn(1);

        service.reportPlayed(USER, DELIVERY_ID, new AdPlayedReportRequestDto(LocalDateTime.now(), null, null));

        verify(deliveryRepository).markPlayed(eq(DELIVERY_ID), any());
        verify(deliveryRepository, never()).markPlayedAndFinished(anyLong(), any(), any(), any(), any());
    }

    @Test
    void playedRejectsAFutureTimestamp() {
        when(deliveryRepository.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery(AdCampaignState.STARTING)));

        assertThatThrownBy(() -> service.reportPlayed(USER, DELIVERY_ID,
                new AdPlayedReportRequestDto(LocalDateTime.now().plusDays(2), null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Waktu putar");
        verify(deliveryRepository, never()).markPlayed(anyLong(), any());
    }

    @Test
    void playedRejectsALoneCoordinate() {
        when(deliveryRepository.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery(AdCampaignState.STARTING)));

        assertThatThrownBy(() -> service.reportPlayed(USER, DELIVERY_ID,
                new AdPlayedReportRequestDto(LocalDateTime.now(), -6.2, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bersama");
    }

    @Test
    void playedWithLocationAfterAlreadyPlayedSalvagesTheLocation() {
        when(deliveryRepository.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery(AdCampaignState.PLAYED)));
        when(deliveryRepository.markPlayedAndFinished(eq(DELIVERY_ID), any(), any(), eq(-6.2), eq(106.8))).thenReturn(0);
        when(deliveryRepository.markFinishedWithLocation(eq(DELIVERY_ID), any(), eq(-6.2), eq(106.8))).thenReturn(1);

        service.reportPlayed(USER, DELIVERY_ID, new AdPlayedReportRequestDto(LocalDateTime.now(), -6.2, 106.8));

        // Falls back to the location update instead of dropping the coordinates
        verify(deliveryRepository).markFinishedWithLocation(eq(DELIVERY_ID), any(), eq(-6.2), eq(106.8));
    }

    @Test
    void locationReportFinishesFromPlayed() {
        when(deliveryRepository.findById(DELIVERY_ID)).thenReturn(Optional.of(delivery(AdCampaignState.PLAYED)));
        when(deliveryRepository.markFinishedWithLocation(eq(DELIVERY_ID), any(), eq(-6.1), eq(106.7))).thenReturn(1);

        service.reportLocation(USER, DELIVERY_ID, new AdLocationReportRequestDto(-6.1, 106.7));

        verify(deliveryRepository).markFinishedWithLocation(eq(DELIVERY_ID), any(), eq(-6.1), eq(106.7));
    }

    @Test
    void aDeviceCannotReportOnAnotherUsersDelivery() {
        AdDelivery other = delivery(AdCampaignState.PREPARING);
        other.setTargetUsername("someone-else");
        when(deliveryRepository.findById(DELIVERY_ID)).thenReturn(Optional.of(other));

        assertThatThrownBy(() -> service.reportDownloaded(USER, DELIVERY_ID,
                new AdDownloadReportRequestDto(true, "abc", 5L, null)))
                .isInstanceOf(ForbiddenException.class);
        verify(deliveryRepository, never()).markReady(anyLong(), any(), any(), any());
    }

    // ---------- history (merchant, super admin) ----------

    @Test
    void historyFlattensCampaignNameOntoEachBusinessDelivery() {
        Pageable pageable = PageRequest.of(0, 50);
        when(businessContext.requireSuperAdminBusinessId(USER)).thenReturn(BIZ);

        AdDelivery played = delivery(AdCampaignState.PLAYED);
        played.setPlayedAt(LocalDateTime.now());
        when(deliveryRepository.findByBusinessIdOrderByIdDesc(BIZ, pageable))
                .thenReturn(new PageImpl<>(List.of(played), pageable, 1));
        AdCampaign campaign = campaign("sha", 100L);
        campaign.setBrand("Kopi Nusantara");
        when(campaignRepository.findAllById(List.of(CAMPAIGN_ID))).thenReturn(List.of(campaign));
        when(userRepository.findActiveIdsByIdIn(List.of(USER_ID))).thenReturn(List.of(USER_ID)); // still active

        var page = service.history(USER, pageable);

        assertThat(page.getContent()).singleElement().satisfies(row -> {
            assertThat(row.campaignName()).isEqualTo("Promo");
            assertThat(row.brand()).isEqualTo("Kopi Nusantara");
            assertThat(row.state()).isEqualTo(AdCampaignState.PLAYED);
            assertThat(row.playedAt()).isNotNull();
            assertThat(row.targetUsername()).isEqualTo(USER);
            assertThat(row.targetName()).isEqualTo(USER_NAME);   // who it went to (snapshotted)
            assertThat(row.userActive()).isTrue();
        });
    }

    @Test
    void historyFlagsARemovedTargetAsInactiveButStillNamesThem() {
        Pageable pageable = PageRequest.of(0, 50);
        when(businessContext.requireSuperAdminBusinessId(USER)).thenReturn(BIZ);
        when(deliveryRepository.findByBusinessIdOrderByIdDesc(BIZ, pageable))
                .thenReturn(new PageImpl<>(List.of(delivery(AdCampaignState.FINISHED)), pageable, 1));
        when(campaignRepository.findAllById(List.of(CAMPAIGN_ID))).thenReturn(List.of(campaign("sha", 100L)));
        when(userRepository.findActiveIdsByIdIn(List.of(USER_ID))).thenReturn(List.of()); // member removed since

        var row = service.history(USER, pageable).getContent().get(0);

        assertThat(row.targetName()).isEqualTo(USER_NAME);   // still identifiable after removal
        assertThat(row.targetUsername()).isEqualTo(USER);
        assertThat(row.userActive()).isFalse();
    }

    @Test
    void historyIsRejectedForAPlainMember() {
        when(businessContext.requireSuperAdminBusinessId(USER))
                .thenThrow(new ForbiddenException("Hanya super admin"));

        assertThatThrownBy(() -> service.history(USER, PageRequest.of(0, 50)))
                .isInstanceOf(ForbiddenException.class);
        verify(deliveryRepository, never()).findByBusinessIdOrderByIdDesc(any(), any());
    }
}
