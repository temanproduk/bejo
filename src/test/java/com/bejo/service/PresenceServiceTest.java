package com.bejo.service;

import com.bejo.constant.BusinessRole;
import com.bejo.constant.PresenceType;
import com.bejo.dto.request.PresenceClockRequestDto;
import com.bejo.dto.request.PresencePingRequestDto;
import com.bejo.dto.response.PresenceResponseDto;
import com.bejo.entity.Presence;
import com.bejo.entity.User;
import com.bejo.exception.ForbiddenException;
import com.bejo.repository.PresenceRepository;
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
    @Mock private PushNotificationService pushNotificationService;
    @Mock private BusinessContext businessContext;

    private PresenceService service;

    private User admin;
    private User member;

    @BeforeEach
    void setUp() {
        service = new PresenceService(presenceRepository, userRepository, pushNotificationService, businessContext);

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

        // The super admin's devices get a display notification naming the member
        ArgumentCaptor<Map<String, String>> data = captureData();
        verify(pushNotificationService).sendNotificationToUser(eq("boss"),
                eq("budi telah absen masuk!"),
                eq("Pastikan jadwal absen sesuai prosedur. Sukses terus ya!"),
                data.capture());
        assertThat(data.getValue()).containsEntry("presenceType", PresenceType.CLOCK_IN)
                .containsEntry("presenceId", "100");
    }

    @Test
    void clockOutPushesTheClockOutTitle() {
        when(userRepository.findFirstByBusinessIdAndBusinessRoleAndDeletedAtIsNull(BIZ, BusinessRole.SUPER_ADMIN))
                .thenReturn(Optional.of(admin));

        service.clockOut("budi", new PresenceClockRequestDto(-6.2, 106.8, LocalDateTime.now(), null));

        verify(presenceRepository).save(any(Presence.class));
        verify(pushNotificationService).sendNotificationToUser(eq("boss"),
                eq("budi telah absen keluar!"), any(), anyMap());
    }

    @Test
    void clockingInTwiceOnTheSameDayIsRejected() {
        LocalDateTime at = LocalDateTime.of(2026, 7, 13, 9, 30);
        when(presenceRepository.existsByUserIdAndTypeAndPresenceDate(2L, PresenceType.CLOCK_IN, at.toLocalDate()))
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
        when(presenceRepository.existsByUserIdAndTypeAndPresenceDate(2L, PresenceType.CLOCK_OUT, at.toLocalDate()))
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
        when(presenceRepository.existsByUserIdAndTypeAndPresenceDate(2L, PresenceType.CLOCK_IN, today.toLocalDate()))
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
        when(presenceRepository.existsByUserIdAndTypeAndPresenceDate(2L, PresenceType.CLOCK_OUT, out.toLocalDate()))
                .thenReturn(false);
        when(userRepository.findFirstByBusinessIdAndBusinessRoleAndDeletedAtIsNull(BIZ, BusinessRole.SUPER_ADMIN))
                .thenReturn(Optional.of(admin));

        PresenceResponseDto dto = service.clockOut("budi",
                new PresenceClockRequestDto(-6.2, 106.8, out, null));

        assertThat(dto.type()).isEqualTo(PresenceType.CLOCK_OUT);
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
        when(pushNotificationService.sendNotificationToUser(any(), any(), any(), anyMap()))
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

        List<PresenceResponseDto> created = service.ping("boss", new PresencePingRequestDto("Masih di toko?"));

        assertThat(created).hasSize(2);
        assertThat(created).allSatisfy(dto -> {
            assertThat(dto.type()).isEqualTo(PresenceType.PING);
            assertThat(dto.responseDatetime()).isNull();
            assertThat(dto.latitude()).isNull();   // the admin sends no location
        });
        verify(presenceRepository, times(2)).save(any(Presence.class));
        // The note doubles as the push body
        verify(pushNotificationService).sendNotificationToUser(eq("budi"), eq("Ping absen!"), eq("Masih di toko?"), anyMap());
        verify(pushNotificationService).sendNotificationToUser(eq("siti"), eq("Ping absen!"), eq("Masih di toko?"), anyMap());
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
        when(presenceRepository.markResponded(eq(200L), any())).thenReturn(1).thenReturn(0);

        PresenceResponseDto dto = service.respond("budi", 200L);
        assertThat(dto.responseDatetime()).isNotNull();

        // Already responded — the update matches no row, so the second tap is rejected
        assertThatThrownBy(() -> service.respond("budi", 200L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sudah direspons");
    }

    @Test
    void memberCannotRespondToAnotherMembersPing() {
        Presence ping = ping(201L, 3L);   // belongs to user 3, not budi (2)
        when(presenceRepository.findByIdAndBusinessId(201L, BIZ)).thenReturn(Optional.of(ping));

        assertThatThrownBy(() -> service.respond("budi", 201L))
                .isInstanceOf(ForbiddenException.class);

        assertThat(ping.getResponseDatetime()).isNull();
    }

    @Test
    void onlyAPingCanBeResponded() {
        Presence clockIn = ping(202L, 2L);
        clockIn.setType(PresenceType.CLOCK_IN);
        when(presenceRepository.findByIdAndBusinessId(202L, BIZ)).thenReturn(Optional.of(clockIn));

        assertThatThrownBy(() -> service.respond("budi", 202L))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ping");
    }

    // ---------- list ----------

    @Test
    void memberListingIsScopedToThemselves() {
        when(presenceRepository.findByBusinessIdAndUserIdOrderByClockDatetimeDesc(eq(BIZ), eq(2L), any()))
                .thenReturn(Page.empty());

        service.list("budi", null, null, null, PageRequest.of(0, 50));

        verify(presenceRepository).findByBusinessIdAndUserIdOrderByClockDatetimeDesc(eq(BIZ), eq(2L), any());
    }

    @Test
    void memberCannotListAnotherMember() {
        assertThatThrownBy(() -> service.list("budi", 3L, null, null, PageRequest.of(0, 50)))
                .isInstanceOf(ForbiddenException.class);

        verifyNoInteractions(presenceRepository);
    }

    @Test
    void superAdminListsTheWholeBusinessFilteredByMonthYear() {
        when(presenceRepository.findByBusinessIdAndPresenceDateBetweenOrderByClockDatetimeDesc(
                eq(BIZ), any(), any(), any())).thenReturn(Page.empty());

        service.list("boss", null, 2026, 7, PageRequest.of(0, 50));

        // July 2026 → the whole month, inclusive
        verify(presenceRepository).findByBusinessIdAndPresenceDateBetweenOrderByClockDatetimeDesc(
                eq(BIZ), eq(LocalDate.of(2026, 7, 1)), eq(LocalDate.of(2026, 7, 31)), any());
    }

    @Test
    void monthWithoutYearMeansThatMonthOfTheCurrentYear() {
        when(presenceRepository.findByBusinessIdAndPresenceDateBetweenOrderByClockDatetimeDesc(
                eq(BIZ), any(), any(), any())).thenReturn(Page.empty());

        service.list("boss", null, null, 7, PageRequest.of(0, 50));

        int thisYear = LocalDate.now().getYear();
        verify(presenceRepository).findByBusinessIdAndPresenceDateBetweenOrderByClockDatetimeDesc(
                eq(BIZ), eq(LocalDate.of(thisYear, 7, 1)), eq(LocalDate.of(thisYear, 7, 31)), any());
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

    private Presence ping(Long id, Long userId) {
        Presence p = new Presence();
        setId(p, id);
        p.setBusinessId(BIZ);
        p.setUserId(userId);
        p.setUsername("budi");
        p.setName("Budi");
        p.setType(PresenceType.PING);
        p.setClockDatetime(LocalDateTime.now());
        p.setPresenceDate(LocalDate.now());
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
