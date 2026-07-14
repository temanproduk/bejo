package com.bejo.service;

import com.bejo.constant.BusinessRole;
import com.bejo.constant.PresenceType;
import com.bejo.constant.PushType;
import com.bejo.dto.request.PresenceClockRequestDto;
import com.bejo.dto.request.PresencePingRequestDto;
import com.bejo.dto.request.PresenceRespondRequestDto;
import com.bejo.dto.response.PresenceResponseDto;
import com.bejo.entity.Presence;
import com.bejo.entity.User;
import com.bejo.exception.ForbiddenException;
import com.bejo.exception.ResourceNotFoundException;
import com.bejo.repository.PresenceRepository;
import com.bejo.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Employee daily presence (attendance). Members clock in and out with a location and the
 * time of the entry; the business's super admin can ping every member for a spot-check,
 * which the member acknowledges from the app. All three land in the same
 * {@code presences} table (see {@link PresenceType}).
 *
 * <p><b>Who may do what:</b> only a MEMBER clocks in/out (the super admin doesn't take
 * attendance); only the super admin sends pings; a member responds only to their own
 * ping. On the list, the super admin sees the whole business's entries (optionally
 * filtered to one member), a member only ever sees their own.
 *
 * <p>Every push here is <b>best-effort</b>: FCM being down or a token being dead must
 * never fail (or roll back) the attendance entry itself.
 */
@Service
public class PresenceService {

    private static final Logger log = LoggerFactory.getLogger(PresenceService.class);

    private static final String CLOCK_PUSH_BODY = "Pastikan jadwal absen sesuai prosedur. Sukses terus ya!";
    private static final String PING_PUSH_TITLE = "Ping Absen Bejoo!";
    private static final String PING_PUSH_BODY = "Halo Mas/Mbak Bejo Sekalian. Masih pada Semangaat?";

    private final PresenceRepository presenceRepository;
    private final UserRepository userRepository;
    private final PushNotificationService pushNotificationService;
    private final BusinessContext businessContext;

    public PresenceService(PresenceRepository presenceRepository,
                           UserRepository userRepository,
                           PushNotificationService pushNotificationService,
                           BusinessContext businessContext) {
        this.presenceRepository = presenceRepository;
        this.userRepository = userRepository;
        this.pushNotificationService = pushNotificationService;
        this.businessContext = businessContext;
    }

    @Transactional
    public PresenceResponseDto clockIn(String username, PresenceClockRequestDto request) {
        return clock(username, request, PresenceType.CLOCK_IN);
    }

    @Transactional
    public PresenceResponseDto clockOut(String username, PresenceClockRequestDto request) {
        return clock(username, request, PresenceType.CLOCK_OUT);
    }

    private PresenceResponseDto clock(String username, PresenceClockRequestDto request, String type) {
        User user = businessContext.requireUser(username);
        if (user.isSuperAdmin()) {
            throw new ForbiddenException("Super admin tidak melakukan absen");
        }
        // The client sends the time, so a clock far in the future means a wrong device clock
        // (or a forged entry) — a day of slack absorbs any legitimate timezone/skew drift.
        if (request.clockDatetime().isAfter(LocalDateTime.now().plusDays(1))) {
            throw new IllegalArgumentException("clockDatetime tidak boleh di masa depan");
        }
        // One clock-in and one clock-out per member per calendar day. Two simultaneous taps
        // could both pass this check, so the DB's uq_presence_clock_daily index has the last
        // word (a 409 from GlobalExceptionHandler); this is the friendly path.
        LocalDate day = request.clockDatetime().toLocalDate();
        if (presenceRepository.existsByUserIdAndTypeAndPresenceDate(user.getId(), type, day)) {
            throw new IllegalArgumentException(PresenceType.CLOCK_IN.equals(type)
                    ? "Kamu sudah absen masuk hari ini"
                    : "Kamu sudah absen keluar hari ini");
        }

        Presence presence = new Presence();
        presence.setBusinessId(user.getBusinessId());
        presence.setUserId(user.getId());
        presence.setUsername(user.getUsername());
        presence.setName(user.getName());
        presence.setType(type);
        presence.setClockDatetime(request.clockDatetime());
        presence.setPresenceDate(request.clockDatetime().toLocalDate());
        presence.setLatitude(request.latitude());
        presence.setLongitude(request.longitude());
        presence.setNote(request.note());
        presenceRepository.save(presence);
        log.info("[PRESENCE] {} id={} username={} businessId={} at={}",
                type, presence.getId(), username, user.getBusinessId(), request.clockDatetime());

        notifySuperAdmin(user, presence, type);
        return toDto(presence);
    }

    // Rings the business's super admin: "<username> telah absen masuk/keluar!"
    private void notifySuperAdmin(User member, Presence presence, String type) {
        User superAdmin = userRepository
                .findFirstByBusinessIdAndBusinessRoleAndDeletedAtIsNull(member.getBusinessId(), BusinessRole.SUPER_ADMIN)
                .orElse(null);
        if (superAdmin == null) {
            log.warn("[PRESENCE] No super admin for businessId={} — no push sent", member.getBusinessId());
            return;
        }
        String verb = PresenceType.CLOCK_IN.equals(type) ? "masuk" : "keluar";
        String title = member.getUsername() + " telah absen " + verb + "!";
        try {
            pushNotificationService.sendUrgentToUser(
                    superAdmin.getUsername(), pushData(presence, title, CLOCK_PUSH_BODY));
        } catch (RuntimeException e) {
            log.error("[PRESENCE] Push step failed for presenceId={} (entry still recorded): {}",
                    presence.getId(), e.getMessage(), e);
        }
    }

    /**
     * Super-admin spot-check: records one PING entry per targeted member and pushes it to
     * their devices. With no {@code userId} the whole team is pinged; with one, only that
     * member. Each member acknowledges their own entry via {@link #respond}, which stamps
     * {@code responseDatetime}.
     */
    @Transactional
    public List<PresenceResponseDto> ping(String callerUsername, PresencePingRequestDto request) {
        User caller = businessContext.requireUser(callerUsername);
        if (!caller.isSuperAdmin()) {
            throw new ForbiddenException("Hanya super admin yang dapat mengirim ping absen");
        }
        Long targetUserId = request != null ? request.userId() : null;
        List<User> members = resolvePingTargets(caller.getBusinessId(), targetUserId);

        LocalDateTime sentAt = LocalDateTime.now();
        List<PresenceResponseDto> created = new ArrayList<>();
        for (User member : members) {
            Presence presence = new Presence();
            presence.setBusinessId(caller.getBusinessId());
            presence.setUserId(member.getId());
            presence.setUsername(member.getUsername());
            presence.setName(member.getName());
            presence.setType(PresenceType.PING);
            presence.setClockDatetime(sentAt);
            presence.setPresenceDate(sentAt.toLocalDate());
            // note stays null: it's reserved for the member's reply (see respond)
            presenceRepository.save(presence);
            created.add(toDto(presence));

            try {
                pushNotificationService.sendUrgentToUser(
                        member.getUsername(), pushData(presence, PING_PUSH_TITLE, PING_PUSH_BODY));
            } catch (RuntimeException e) {
                log.error("[PRESENCE] Ping push failed for username={} (entry still recorded): {}",
                        member.getUsername(), e.getMessage(), e);
            }
        }
        log.info("[PRESENCE] PING sent to {} member(s) of businessId={} by {} ({})",
                created.size(), caller.getBusinessId(), callerUsername,
                targetUserId != null ? "userId=" + targetUserId : "whole team");
        return created;
    }

    /**
     * Who this ping goes to: just {@code userId}, or the whole team when it's omitted. The id
     * must be an active MEMBER of the caller's own business — an unknown id, someone else's
     * staff, or the super admin themselves is a 400 (and nothing is sent).
     */
    private List<User> resolvePingTargets(Long businessId, Long userId) {
        List<User> members = userRepository.findByBusinessIdAndBusinessRoleAndDeletedAtIsNullOrderByCreatedAtAsc(
                businessId, BusinessRole.MEMBER);
        if (userId == null) {
            if (members.isEmpty()) {
                throw new IllegalArgumentException("Belum ada anggota tim yang dapat dikirimi ping");
            }
            return members;
        }
        return members.stream()
                .filter(m -> m.getId().equals(userId))
                .findFirst()
                .map(List::of)
                .orElseThrow(() -> new IllegalArgumentException("Anggota tim tidak ditemukan: " + userId));
    }

    /**
     * The member taps the ping in the app: stamps {@code responseDatetime} (exactly once),
     * plus an optional {@code note} — their reply, which the super admin then reads in the
     * history. The ping itself carries no note; this is what fills it.
     */
    @Transactional
    public PresenceResponseDto respond(String username, Long presenceId, PresenceRespondRequestDto request) {
        User user = businessContext.requireUser(username);
        Presence presence = presenceRepository.findByIdAndBusinessId(presenceId, user.getBusinessId())
                .orElseThrow(() -> new ResourceNotFoundException("Presence not found: " + presenceId));
        if (!presence.getUserId().equals(user.getId())) {
            throw new ForbiddenException("Ping ini bukan milik Anda");
        }
        if (!PresenceType.PING.equals(presence.getType())) {
            throw new IllegalArgumentException("Hanya ping yang dapat direspons");
        }

        // Conditional update, not a read-then-write: two simultaneous taps both see a null
        // responseDatetime above, but only one of them updates a row here.
        LocalDateTime respondedAt = LocalDateTime.now();
        String note = request != null ? request.note() : null;
        if (presenceRepository.markResponded(presenceId, respondedAt, note) == 0) {
            throw new IllegalArgumentException("Ping ini sudah direspons");
        }
        presence.setResponseDatetime(respondedAt);
        presence.setNote(note);
        log.info("[PRESENCE] PING id={} responded by username={}", presenceId, username);
        return toDto(presence);
    }

    /**
     * Attendance history, newest first. The super admin sees the whole business and may
     * filter to one member with {@code userId}; a member is always scoped to their own
     * entries (passing someone else's {@code userId} is a 403). {@code year} (+ optional
     * {@code month}) narrows it to that calendar period; {@code month} alone means that
     * month of the current year.
     */
    @Transactional(readOnly = true)
    public Page<PresenceResponseDto> list(String username, Long userId, Integer year, Integer month, Pageable pageable) {
        User caller = businessContext.requireUser(username);
        Long businessId = caller.getBusinessId();

        Long scopedUserId;
        if (caller.isSuperAdmin()) {
            scopedUserId = userId;   // null = every member of the business
        } else {
            if (userId != null && !userId.equals(caller.getId())) {
                throw new ForbiddenException("Anggota hanya dapat melihat absensinya sendiri");
            }
            scopedUserId = caller.getId();
        }

        LocalDate[] range = monthYearRange(year, month);
        Page<Presence> entries;
        if (range == null) {
            entries = scopedUserId == null
                    ? presenceRepository.findByBusinessIdOrderByClockDatetimeDesc(businessId, pageable)
                    : presenceRepository.findByBusinessIdAndUserIdOrderByClockDatetimeDesc(businessId, scopedUserId, pageable);
        } else {
            entries = scopedUserId == null
                    ? presenceRepository.findByBusinessIdAndPresenceDateBetweenOrderByClockDatetimeDesc(
                            businessId, range[0], range[1], pageable)
                    : presenceRepository.findByBusinessIdAndUserIdAndPresenceDateBetweenOrderByClockDatetimeDesc(
                            businessId, scopedUserId, range[0], range[1], pageable);
        }
        if (entries.isEmpty()) return entries.map(p -> toDto(p, true));

        // Which of this page's members are still with the business (one query, no N+1) —
        // a removed member's entries stay in the history, flagged userActive=false.
        List<Long> userIds = entries.getContent().stream().map(Presence::getUserId).distinct().toList();
        Set<Long> active = new HashSet<>(userRepository.findActiveIdsByIdIn(userIds));
        return entries.map(p -> toDto(p, active.contains(p.getUserId())));
    }

    /**
     * The inclusive [from, to] days the filter covers, or null for "no period filter".
     * A {@code month} without a {@code year} means that month of the <b>current</b> year.
     */
    private LocalDate[] monthYearRange(Integer year, Integer month) {
        if (year == null && month == null) return null;
        int y = year != null ? year : LocalDate.now().getYear();
        if (y < 2000 || y > 2100) {
            throw new IllegalArgumentException("year harus antara 2000 dan 2100");
        }
        if (month == null) {
            return new LocalDate[]{LocalDate.of(y, 1, 1), LocalDate.of(y, 12, 31)};
        }
        if (month < 1 || month > 12) {
            throw new IllegalArgumentException("month harus antara 1 dan 12");
        }
        YearMonth ym = YearMonth.of(y, month);
        return new LocalDate[]{ym.atDay(1), ym.atEndOfMonth()};
    }

    /**
     * The data-only push payload. {@code type=PRESENCE} ({@link PushType}) is what tells the
     * app this is an attendance event and not a payment; {@code presenceType} says which kind.
     * {@code title}/{@code body} are the text the app renders (FCM draws nothing itself), and
     * {@code presenceId} is the entry to open — for a PING, the one to respond to.
     */
    private Map<String, String> pushData(Presence presence, String title, String body) {
        Map<String, String> data = new HashMap<>();
        data.put("type", PushType.PRESENCE);
        data.put("title", title);
        data.put("body", body);
        data.put("presenceType", presence.getType());
        data.put("presenceId", String.valueOf(presence.getId()));
        data.put("userId", String.valueOf(presence.getUserId()));
        data.put("username", presence.getUsername());
        data.put("clockDatetime", String.valueOf(presence.getClockDatetime()));
        return data;
    }

    // The write paths always concern a live member, so they report userActive = true.
    private PresenceResponseDto toDto(Presence p) {
        return toDto(p, true);
    }

    private PresenceResponseDto toDto(Presence p, boolean userActive) {
        return new PresenceResponseDto(p.getId(), p.getUserId(), p.getUsername(), p.getName(), p.getType(),
                p.getClockDatetime(), p.getPresenceDate(), p.getLatitude(), p.getLongitude(),
                p.getNote(), p.getResponseDatetime(), userActive, p.getCreatedAt());
    }
}
