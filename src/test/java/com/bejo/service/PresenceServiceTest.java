package com.bejo.service;

import com.bejo.constant.BusinessRole;
import com.bejo.constant.PresenceType;
import com.bejo.constant.PushType;
import com.bejo.dto.request.PresenceClockRequestDto;
import com.bejo.dto.request.PresencePingRequestDto;
import com.bejo.dto.request.PresenceRespondRequestDto;
import com.bejo.dto.response.PingResultResponseDto;
import com.bejo.dto.response.PresenceResponseDto;
import com.bejo.entity.Presence;
import com.bejo.entity.User;
import com.bejo.exception.ForbiddenException;
import com.bejo.repository.PresenceRepository;
import com.bejo.repository.SessionRepository;
import com.bejo.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PresenceServiceTest {

    private static final Long BIZ = 1L;

    @Mock private PresenceRepository presenceRepository;
    @Mock private UserRepository userRepository;
    @Mock private SessionRepository sessionRepository;
    @Mock private PushNotificationService pushNotificationService;
    @Mock private BusinessContext businessContext;

    private PresenceService service;

    private User admin;
    private User member;

    @BeforeEach
    void setUp() {
        service = new PresenceService(presenceRepository, userRepository, sessionRepository,
                pushNotificationService, businessContext);

        // By default every member has a device registered for push (the ping flow skips
        // members that have none) — the no-device tests override this. Null-safe because
        // Mockito replays the matcher with a null argument while recording an override.
        lenient().when(sessionRepository.findUsernamesWithPushToken(any())).thenAnswer(inv -> {
            Collection<String> usernames = inv.getArgument(0);
            return usernames == null ? List.of() : List.copyOf(usernames);
        });

        admin = user(1L, "boss", "Boss", BusinessRole.SUPER_ADMIN);
        member = user(2L, "budi", "Budi", BusinessRole.MEMBER);
        lenient().when(businessContext.requireUser("boss")).thenReturn(admin);
        lenient().when(businessContext.requireUser("budi")).thenReturn(member);

        // The saved entry gets its id from the DB — the push payload carries it
        AtomicLong ids = new AtomicLong(100);
        lenient().when(presenceRepository.save(any(Presence.class))).thenAnswer(inv -> {
            Presence p = inv.getArgument(0);
            if (p.getId() == null) setId(p, ids.getAndIncrement());
            return p;
        });
    }

    // ---------- clock in / out ----------

    @Test
    void clockInRecordsEntryAndPushesToSuperAdmin() {
        when(userRepository.findFirstByBusinessIdAndBusinessRoleAndDeletedAtIsNull(BIZ, BusinessRole.SUPER_ADMIN))
                .thenReturn(Optional.of(admin));
        LocalDateTime at = LocalDateTime.of(2026, 7, 13, 8, 5);

        PresenceResponseDto dto = service.clockIn("budi",
                new PresenceClockRequestDto(-6.2, 106.8, at, "Tiba di toko"));

        ArgumentCaptor<Presence> saved = ArgumentCaptor.forClass(Presence.class);
        verify(presenceRepository).save(saved.capture());
        Presence p = saved.getValue();
        assertThat(p.getType()).isEqualTo(PresenceType.CLOCK_IN);
        assertThat(p.getBusinessId()).isEqualTo(BIZ);
        assertThat(p.getUserId()).isEqualTo(2L);
        assertThat(p.getUsername()).isEqualTo("budi");
        assertThat(p.getClockDatetime()).isEqualTo(at);
        assertThat(p.getPresenceDate()).isEqualTo(LocalDate.of(2026, 7, 13));
        assertThat(p.getLatitude()).isEqualTo(-6.2);
        assertThat(p.getLongitude()).isEqualTo(106.8);
        assertThat(p.getNote()).isEqualTo("Tiba di toko");
        assertThat(p.getResponseDatetime()).isNull();
        assertThat(dto.type()).isEqualTo(PresenceType.CLOCK_IN);

        // The super admin's devices get a data-only push the app renders itself
        ArgumentCaptor<Map<String, String>> data = captureData();
        verify(pushNotificationService).sendUrgentToUser(eq("boss"), data.capture());
        assertThat(data.getValue())
                .containsEntry("type", PushType.PRESENCE)          // not a TRANSACTION push
                .containsEntry("title", "budi telah absen masuk!")
                .containsEntry("body", "Pastikan jadwal absen sesuai prosedur. Sukses terus ya!")
                .containsEntry("presenceType", PresenceType.CLOCK_IN)
                .containsEntry("presenceId", "100");
    }

    @Test
    void clockOutPushesTheClockOutTitle() {
        when(userRepository.findFirstByBusinessIdAndBusinessRoleAndDeletedAtIsNull(BIZ, BusinessRole.SUPER_ADMIN))
                .thenReturn(Optional.of(admin));

        when(presenceRepository.existsByUserIdAndTypeAndClockDatetimeBetween(
                eq(2L), eq(PresenceType.CLOCK_OUT), any(), any())).thenReturn(false);
        when(presenceRepository.existsByUserIdAndTypeAndClockDatetimeBetween(
                eq(2L), eq(PresenceType.CLOCK_IN), any(), any())).thenReturn(true);   // clocked in this morning

        service.clockOut("budi", new PresenceClockRequestDto(-6.2, 106.8, LocalDateTime.now(), null));

        verify(presenceRepository).save(any(Presence.class));
        ArgumentCaptor<Map<String, String>> data = captureData();
        verify(pushNotificationService).sendUrgentToUser(eq("boss"), data.capture());
        assertThat(data.getValue()).containsEntry("title", "budi telah absen keluar!");
    }

    @Test
    void clockingInTwiceOnTheSameDayIsRejected() {
        LocalDateTime at = LocalDateTime.of(2026, 7, 13, 9, 30);
        when(presenceRepository.existsByUserIdAndTypeAndClockDatetimeBetween(eq(2L), eq(PresenceType.CLOCK_IN), any(), any()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.clockIn("budi",
                new PresenceClockRequestDto(-6.2, 106.8, at, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sudah absen masuk hari ini");

        verify(presenceRepository, never()).save(any());
        verifyNoInteractions(pushNotificationService);
    }

    @Test
    void clockingOutTwiceOnTheSameDayIsRejected() {
        LocalDateTime at = LocalDateTime.of(2026, 7, 13, 18, 0);
        when(presenceRepository.existsByUserIdAndTypeAndClockDatetimeBetween(eq(2L), eq(PresenceType.CLOCK_OUT), any(), any()))
                .thenReturn(true);

        assertThatThrownBy(() -> service.clockOut("budi",
                new PresenceClockRequestDto(-6.2, 106.8, at, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sudah absen keluar hari ini");

        verify(presenceRepository, never()).save(any());
    }

    @Test
    void clockingInIsAllowedAgainOnTheNextDay() {
        // Yesterday's clock-in doesn't block today's: the check is per calendar day
        LocalDateTime today = LocalDateTime.of(2026, 7, 14, 8, 0);
        when(presenceRepository.existsByUserIdAndTypeAndClockDatetimeBetween(eq(2L), eq(PresenceType.CLOCK_IN), any(), any()))
                .thenReturn(false);
        when(userRepository.findFirstByBusinessIdAndBusinessRoleAndDeletedAtIsNull(BIZ, BusinessRole.SUPER_ADMIN))
                .thenReturn(Optional.of(admin));

        PresenceResponseDto dto = service.clockIn("budi",
                new PresenceClockRequestDto(-6.2, 106.8, today, null));

        assertThat(dto.presenceDate()).isEqualTo(LocalDate.of(2026, 7, 14));
        verify(presenceRepository).save(any(Presence.class));
    }

    @Test
    void aClockOutOnTheSameDayAsAClockInIsAllowed() {
        // The limit is one of EACH type per day — clocking out after clocking in is the norm
        LocalDateTime out = LocalDateTime.of(2026, 7, 13, 17, 0);
        when(presenceRepository.existsByUserIdAndTypeAndClockDatetimeBetween(eq(2L), eq(PresenceType.CLOCK_OUT), any(), any()))
                .thenReturn(false);
        when(presenceRepository.existsByUserIdAndTypeAndClockDatetimeBetween(eq(2L), eq(PresenceType.CLOCK_IN), any(), any()))
                .thenReturn(true);   // they clocked in this morning
        when(userRepository.findFirstByBusinessIdAndBusinessRoleAndDeletedAtIsNull(BIZ, BusinessRole.SUPER_ADMIN))
                .thenReturn(Optional.of(admin));

        PresenceResponseDto dto = service.clockOut("budi",
                new PresenceClockRequestDto(-6.2, 106.8, out, null));

        assertThat(dto.type()).isEqualTo(PresenceType.CLOCK_OUT);
    }

    @Test
    void clockingOutWithoutClockingInIsRejected() {
        LocalDateTime out = LocalDateTime.of(2026, 7, 13, 17, 0);
        // No clock-out yet today, but no clock-in either — you can't leave a shift you
        // never started
        when(presenceRepository.existsByUserIdAndTypeAndClockDatetimeBetween(
                eq(2L), eq(PresenceType.CLOCK_OUT), any(), any())).thenReturn(false);
        when(presenceRepository.existsByUserIdAndTypeAndClockDatetimeBetween(
                eq(2L), eq(PresenceType.CLOCK_IN), any(), any())).thenReturn(false);

        assertThatThrownBy(() -> service.clockOut("budi",
                new PresenceClockRequestDto(-6.2, 106.8, out, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("belum absen masuk hari ini");

        verify(presenceRepository, never()).save(any());
        verifyNoInteractions(pushNotificationService);
    }

    @Test
    void superAdminCannotClockIn() {
        assertThatThrownBy(() -> service.clockIn("boss",
                new PresenceClockRequestDto(-6.2, 106.8, LocalDateTime.now(), null)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("Super admin");

        verifyNoInteractions(presenceRepository, pushNotificationService);
    }

    @Test
    void clockDatetimeInTheFutureIsRejected() {
        assertThatThrownBy(() -> service.clockIn("budi",
                new PresenceClockRequestDto(-6.2, 106.8, LocalDateTime.now().plusDays(3), null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("masa depan");

        verify(presenceRepository, never()).save(any());
        verifyNoInteractions(pushNotificationService);
    }

    @Test
    void clockInIsRecordedEvenWhenThePushFails() {
        when(userRepository.findFirstByBusinessIdAndBusinessRoleAndDeletedAtIsNull(BIZ, BusinessRole.SUPER_ADMIN))
                .thenReturn(Optional.of(admin));
        when(pushNotificationService.sendUrgentToUser(any(), anyMap()))
                .thenThrow(new RuntimeException("FCM down"));

        PresenceResponseDto dto = service.clockIn("budi",
                new PresenceClockRequestDto(-6.2, 106.8, LocalDateTime.now(), null));

        assertThat(dto.id()).isEqualTo(100L);
        verify(presenceRepository).save(any(Presence.class));
    }

    // ---------- ping ----------

    @Test
    void pingRecordsAnEntryPerMemberAndPushesEach() {
        User other = user(3L, "siti", "Siti", BusinessRole.MEMBER);
        when(userRepository.findByBusinessIdAndBusinessRoleAndDeletedAtIsNullOrderByCreatedAtAsc(BIZ, BusinessRole.MEMBER))
                .thenReturn(List.of(member, other));

        List<PresenceResponseDto> created = service.ping("boss", new PresencePingRequestDto(null)).presences();

        assertThat(created).hasSize(2);
        assertThat(created).allSatisfy(dto -> {
            assertThat(dto.type()).isEqualTo(PresenceType.PING);
            assertThat(dto.responseDatetime()).isNull();
            assertThat(dto.latitude()).isNull();   // the admin sends no location
        });
        verify(presenceRepository, times(2)).save(any(Presence.class));
        ArgumentCaptor<Map<String, String>> data = captureData();
        verify(pushNotificationService).sendUrgentToUser(eq("budi"), data.capture());
        verify(pushNotificationService).sendUrgentToUser(eq("siti"), anyMap());
        assertThat(data.getValue())
                .containsEntry("type", PushType.PRESENCE)
                .containsEntry("presenceType", PresenceType.PING)
                .containsEntry("title", "Ping Absen Bejoo!")
                .containsEntry("body", "Halo Mas/Mbak Bejo Sekalian. Masih pada Semangaat?");

        // The super admin sent it — their own devices must NOT be pinged
        verify(pushNotificationService, never()).sendUrgentToUser(eq("boss"), anyMap());
    }

    @Test
    void pingCanTargetOneMemberOnly() {
        User other = user(3L, "siti", "Siti", BusinessRole.MEMBER);
        when(userRepository.findByBusinessIdAndBusinessRoleAndDeletedAtIsNullOrderByCreatedAtAsc(BIZ, BusinessRole.MEMBER))
                .thenReturn(List.of(member, other));

        // Only Siti is targeted — Budi is not pinged at all
        List<PresenceResponseDto> created = service.ping("boss", new PresencePingRequestDto(3L)).presences();

        assertThat(created).hasSize(1);
        assertThat(created.get(0).username()).isEqualTo("siti");
        verify(presenceRepository, times(1)).save(any(Presence.class));
        verify(pushNotificationService).sendUrgentToUser(eq("siti"), anyMap());
        verify(pushNotificationService, never()).sendUrgentToUser(eq("budi"), anyMap());
    }

    @Test
    void pingToAnUnknownMemberIsRejectedAndNothingIsSent() {
        when(userRepository.findByBusinessIdAndBusinessRoleAndDeletedAtIsNullOrderByCreatedAtAsc(BIZ, BusinessRole.MEMBER))
                .thenReturn(List.of(member));

        // 99 isn't a member of this business
        assertThatThrownBy(() -> service.ping("boss", new PresencePingRequestDto(99L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tidak ditemukan");

        verify(presenceRepository, never()).save(any());
        verifyNoInteractions(pushNotificationService);
    }

    @Test
    void pingTargetingTheSuperAdminIsRejected() {
        when(userRepository.findByBusinessIdAndBusinessRoleAndDeletedAtIsNullOrderByCreatedAtAsc(BIZ, BusinessRole.MEMBER))
                .thenReturn(List.of(member));

        // The super admin (id 1) is not a MEMBER — they don't take attendance
        assertThatThrownBy(() -> service.ping("boss", new PresencePingRequestDto(1L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tidak ditemukan");

        verify(presenceRepository, never()).save(any());
    }

    @Test
    void aMemberPingedWithinTheHourIsSkippedOnATeamPing() {
        User other = user(3L, "siti", "Siti", BusinessRole.MEMBER);
        when(userRepository.findByBusinessIdAndBusinessRoleAndDeletedAtIsNullOrderByCreatedAtAsc(BIZ, BusinessRole.MEMBER))
                .thenReturn(List.of(member, other));
        // Budi was pinged 10 minutes ago; Siti never
        when(presenceRepository.findLastPingAtByUserIds(eq(BIZ), any()))
                .thenReturn(List.<Object[]>of(new Object[]{2L, LocalDateTime.now().minusMinutes(10)}));

        List<PresenceResponseDto> created = service.ping("boss", new PresencePingRequestDto(null)).presences();

        // Budi is skipped, but he doesn't block Siti
        assertThat(created).hasSize(1);
        assertThat(created.get(0).username()).isEqualTo("siti");
        verify(pushNotificationService).sendUrgentToUser(eq("siti"), anyMap());
        verify(pushNotificationService, never()).sendUrgentToUser(eq("budi"), anyMap());
    }

    @Test
    void aTargetedPingWithinTheHourIsRejected() {
        when(userRepository.findByBusinessIdAndBusinessRoleAndDeletedAtIsNullOrderByCreatedAtAsc(BIZ, BusinessRole.MEMBER))
                .thenReturn(List.of(member));
        when(presenceRepository.findLastPingAtByUserIds(eq(BIZ), any()))
                .thenReturn(List.<Object[]>of(new Object[]{2L, LocalDateTime.now().minusMinutes(10)}));

        // The admin asked for this one member — tell them it didn't happen
        assertThatThrownBy(() -> service.ping("boss", new PresencePingRequestDto(2L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("baru saja di-ping")
                .hasMessageContaining("50 menit");

        verify(presenceRepository, never()).save(any());
        verifyNoInteractions(pushNotificationService);
    }

    @Test
    void aTeamPingIsRejectedWhenEveryMemberIsCoolingDown() {
        when(userRepository.findByBusinessIdAndBusinessRoleAndDeletedAtIsNullOrderByCreatedAtAsc(BIZ, BusinessRole.MEMBER))
                .thenReturn(List.of(member));
        when(presenceRepository.findLastPingAtByUserIds(eq(BIZ), any()))
                .thenReturn(List.<Object[]>of(new Object[]{2L, LocalDateTime.now().minusMinutes(30)}));

        // Skipping everyone would be a silent no-op — say so instead
        assertThatThrownBy(() -> service.ping("boss", new PresencePingRequestDto(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Tidak ada anggota tim yang dapat di-ping");

        verify(presenceRepository, never()).save(any());
    }

    @Test
    void pingingIsAllowedAgainOnceTheHourHasPassed() {
        when(userRepository.findByBusinessIdAndBusinessRoleAndDeletedAtIsNullOrderByCreatedAtAsc(BIZ, BusinessRole.MEMBER))
                .thenReturn(List.of(member));
        // Exactly at the boundary — an hour and a second ago is no longer "within the hour"
        when(presenceRepository.findLastPingAtByUserIds(eq(BIZ), any()))
                .thenReturn(List.<Object[]>of(new Object[]{2L, LocalDateTime.now().minusHours(1).minusSeconds(1)}));

        List<PresenceResponseDto> created = service.ping("boss", new PresencePingRequestDto(2L)).presences();

        assertThat(created).hasSize(1);
        verify(pushNotificationService).sendUrgentToUser(eq("budi"), anyMap());
    }

    @Test
    void aMemberWithNoDeviceIsSkippedAndReportedOnATeamPing() {
        User other = user(3L, "siti", "Siti", BusinessRole.MEMBER);
        when(userRepository.findByBusinessIdAndBusinessRoleAndDeletedAtIsNullOrderByCreatedAtAsc(BIZ, BusinessRole.MEMBER))
                .thenReturn(List.of(member, other));
        // Only Siti has a device registered for push
        when(sessionRepository.findUsernamesWithPushToken(any())).thenReturn(List.of("siti"));

        PingResultResponseDto result = service.ping("boss", new PresencePingRequestDto(null));

        // Budi gets no PING row at all — recording one would burn his hour-long cooldown
        // on a message that could never reach him
        assertThat(result.pinged()).isEqualTo(1);
        assertThat(result.totalMembers()).isEqualTo(2);
        assertThat(result.presences()).singleElement()
                .satisfies(p -> assertThat(p.username()).isEqualTo("siti"));
        assertThat(result.skipped()).singleElement().satisfies(s -> {
            assertThat(s.username()).isEqualTo("budi");
            assertThat(s.reason()).isEqualTo("NO_DEVICE");
        });
        verify(presenceRepository, times(1)).save(any(Presence.class));
        verify(pushNotificationService, never()).sendUrgentToUser(eq("budi"), anyMap());
    }

    @Test
    void aTargetedPingToAMemberWithNoDeviceIsRejected() {
        when(userRepository.findByBusinessIdAndBusinessRoleAndDeletedAtIsNullOrderByCreatedAtAsc(BIZ, BusinessRole.MEMBER))
                .thenReturn(List.of(member));
        when(sessionRepository.findUsernamesWithPushToken(any())).thenReturn(List.of());

        assertThatThrownBy(() -> service.ping("boss", new PresencePingRequestDto(2L)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("belum memiliki perangkat");

        verify(presenceRepository, never()).save(any());
        verifyNoInteractions(pushNotificationService);
    }

    @Test
    void thePingResultCountsTheWholeTeam() {
        User other = user(3L, "siti", "Siti", BusinessRole.MEMBER);
        when(userRepository.findByBusinessIdAndBusinessRoleAndDeletedAtIsNullOrderByCreatedAtAsc(BIZ, BusinessRole.MEMBER))
                .thenReturn(List.of(member, other));

        PingResultResponseDto result = service.ping("boss", new PresencePingRequestDto(null));

        assertThat(result.pinged()).isEqualTo(2);
        assertThat(result.totalMembers()).isEqualTo(2);
        assertThat(result.skipped()).isEmpty();
    }

    @Test
    void memberCannotPing() {
        assertThatThrownBy(() -> service.ping("budi", new PresencePingRequestDto(null)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("super admin");

        verifyNoInteractions(presenceRepository, pushNotificationService);
    }

    @Test
    void pingWithoutMembersIsRejected() {
        when(userRepository.findByBusinessIdAndBusinessRoleAndDeletedAtIsNullOrderByCreatedAtAsc(BIZ, BusinessRole.MEMBER))
                .thenReturn(List.of());

        assertThatThrownBy(() -> service.ping("boss", new PresencePingRequestDto(null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("anggota tim");

        verifyNoInteractions(presenceRepository, pushNotificationService);
    }

    // ---------- respond ----------

    @Test
    void memberRespondsToOwnPingOnce() {
        Presence ping = ping(200L, 2L);
        when(presenceRepository.findByIdAndBusinessId(200L, BIZ)).thenReturn(Optional.of(ping));
        // The conditional update only touches a row while responseDatetime is still null
        when(presenceRepository.markResponded(eq(200L), any(), any(), any(), any())).thenReturn(1).thenReturn(0);

        // The reply carries where the member is + their note — the ping itself had neither
        PresenceResponseDto dto = service.respond("budi", 200L,
                new PresenceRespondRequestDto(-6.2, 106.8, "Lagi antar pesanan"));
        assertThat(dto.responseDatetime()).isNotNull();
        assertThat(dto.note()).isEqualTo("Lagi antar pesanan");
        assertThat(dto.latitude()).isEqualTo(-6.2);
        assertThat(dto.longitude()).isEqualTo(106.8);
        verify(presenceRepository).markResponded(eq(200L), any(), eq("Lagi antar pesanan"), eq(-6.2), eq(106.8));

        // Already responded — the update matches no row, so the second tap is rejected
        assertThatThrownBy(() -> service.respond("budi", 200L, reply()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sudah direspons");
    }

    @Test
    void memberCannotRespondToAnotherMembersPing() {
        Presence ping = ping(201L, 3L);   // belongs to user 3, not budi (2)
        when(presenceRepository.findByIdAndBusinessId(201L, BIZ)).thenReturn(Optional.of(ping));

        assertThatThrownBy(() -> service.respond("budi", 201L, reply()))
                .isInstanceOf(ForbiddenException.class);

        assertThat(ping.getResponseDatetime()).isNull();
    }

    @Test
    void aPingOlderThan24HoursCanNoLongerBeResponded() {
        Presence stale = ping(203L, 2L);
        stale.setClockDatetime(LocalDateTime.now().minusHours(25));
        when(presenceRepository.findByIdAndBusinessId(203L, BIZ)).thenReturn(Optional.of(stale));

        assertThatThrownBy(() -> service.respond("budi", 203L, reply()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("kedaluwarsa");

        verify(presenceRepository, never()).markResponded(any(), any(), any(), any(), any());
    }

    @Test
    void aPingFromLastNightIsStillAnswerableThisMorning() {
        // The window is a rolling 24h, not the calendar date: a ping sent at 23:00 must not
        // die an hour later at midnight.
        Presence lastNight = ping(204L, 2L);
        lastNight.setClockDatetime(LocalDateTime.now().minusHours(10));
        when(presenceRepository.findByIdAndBusinessId(204L, BIZ)).thenReturn(Optional.of(lastNight));
        when(presenceRepository.markResponded(eq(204L), any(), any(), any(), any())).thenReturn(1);

        PresenceResponseDto dto = service.respond("budi", 204L, reply());

        assertThat(dto.responseDatetime()).isNotNull();
    }

    @Test
    void onlyAPingCanBeResponded() {
        Presence clockIn = ping(202L, 2L);
        clockIn.setType(PresenceType.CLOCK_IN);
        when(presenceRepository.findByIdAndBusinessId(202L, BIZ)).thenReturn(Optional.of(clockIn));

        assertThatThrownBy(() -> service.respond("budi", 202L, reply()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ping");
    }

    // ---------- list ----------

    @Test
    void memberListingIsScopedToThemselves() {
        when(presenceRepository.findByBusinessIdAndUserIdOrderByClockDatetimeDescIdDesc(eq(BIZ), eq(2L), any()))
                .thenReturn(Page.empty());

        service.list("budi", null, null, null, PageRequest.of(0, 50));

        verify(presenceRepository).findByBusinessIdAndUserIdOrderByClockDatetimeDescIdDesc(eq(BIZ), eq(2L), any());
    }

    @Test
    void memberCannotListAnotherMember() {
        assertThatThrownBy(() -> service.list("budi", 3L, null, null, PageRequest.of(0, 50)))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(presenceRepository);
    }

    @Test
    void superAdminListsTheWholeBusinessFilteredByMonthYear() {
        when(presenceRepository.findByBusinessIdAndClockDatetimeBetweenOrderByClockDatetimeDescIdDesc(
                eq(BIZ), any(), any(), any())).thenReturn(Page.empty());

        service.list("boss", null, 2026, 7, PageRequest.of(0, 50));

        // July 2026 → the whole month, inclusive
        verify(presenceRepository).findByBusinessIdAndClockDatetimeBetweenOrderByClockDatetimeDescIdDesc(
                eq(BIZ), eq(LocalDate.of(2026, 7, 1).atStartOfDay()),
                eq(LocalDate.of(2026, 7, 31).atTime(java.time.LocalTime.MAX)), any());
    }

    @Test
    void monthWithoutYearMeansThatMonthOfTheCurrentYear() {
        when(presenceRepository.findByBusinessIdAndClockDatetimeBetweenOrderByClockDatetimeDescIdDesc(
                eq(BIZ), any(), any(), any())).thenReturn(Page.empty());

        service.list("boss", null, null, 7, PageRequest.of(0, 50));

        int thisYear = LocalDate.now().getYear();
        verify(presenceRepository).findByBusinessIdAndClockDatetimeBetweenOrderByClockDatetimeDescIdDesc(
                eq(BIZ), eq(LocalDate.of(thisYear, 7, 1).atStartOfDay()),
                eq(LocalDate.of(thisYear, 7, 31).atTime(java.time.LocalTime.MAX)), any());
    }

    @Test
    void invalidMonthIsRejected() {
        assertThatThrownBy(() -> service.list("boss", null, 2026, 13, PageRequest.of(0, 50)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("month");

        verifyNoInteractions(presenceRepository);
    }

    // ---------- fixtures ----------

    private User user(Long id, String username, String name, String role) {
        User u = new User();
        setId(u, id);
        u.setUsername(username);
        u.setName(name);
        u.setBusinessId(BIZ);
        u.setBusinessRole(role);
        return u;
    }

    private PresenceRespondRequestDto reply() {
        return new PresenceRespondRequestDto(-6.2, 106.8, null);
    }

    private Presence ping(Long id, Long userId) {
        Presence p = new Presence();
        setId(p, id);
        p.setBusinessId(BIZ);
        p.setUserId(userId);
        p.setUsername("budi");
        p.setName("Budi");
        p.setType(PresenceType.PING);
        p.setClockDatetime(LocalDateTime.now());
        return p;
    }

    @SuppressWarnings("unchecked")
    private ArgumentCaptor<Map<String, String>> captureData() {
        return ArgumentCaptor.forClass(Map.class);
    }

    private static void setId(Object entity, Long id) {
        try {
            var f = entity.getClass().getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
