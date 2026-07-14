package com.bejo.service;

import com.bejo.constant.BusinessRole;
import com.bejo.constant.PresenceType;
import com.bejo.constant.PushType;
import com.bejo.dto.request.PresenceClockRequestDto;
import com.bejo.dto.request.PresencePingRequestDto;
import com.bejo.dto.request.PresenceRespondRequestDto;
import com.bejo.dto.response.PingResultResponseDto;
import com.bejo.dto.response.PingResultResponseDto.SkippedMemberDto;
import com.bejo.dto.response.PresenceResponseDto;
import com.bejo.entity.Presence;
import com.bejo.entity.User;
import com.bejo.exception.ForbiddenException;
import com.bejo.exception.ResourceNotFoundException;
import com.bejo.repository.PresenceRepository;
import com.bejo.repository.SessionRepository;
import com.bejo.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
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

    // A member can't be pinged more often than this — a spot-check, not a nag
    private static final Duration PING_COOLDOWN = Duration.ofHours(1);

    // How long a ping stays answerable after it was sent
    private static final Duration PING_RESPONSE_WINDOW = Duration.ofHours(24);

    // Why a member was left out of a ping (reported back to the super admin)
    private static final String SKIPPED_NO_DEVICE = "NO_DEVICE";
    private static final String SKIPPED_COOLDOWN = "COOLDOWN";

    private static final String CLOCK_PUSH_BODY = "Pastikan jadwal absen sesuai prosedur. Sukses terus ya!";
    private static final String PING_PUSH_TITLE = "Ping Absen Bejoo!";
    private static final String PING_PUSH_BODY = "Halo Mas/Mbak Bejo Sekalian. Masih pada Semangaat?";

    private final PresenceRepository presenceRepository;
    private final UserRepository userRepository;
    private final SessionRepository sessionRepository;
    private final PushNotificationService pushNotificationService;
    private final BusinessContext businessContext;

    public PresenceService(PresenceRepository presenceRepository,
                           UserRepository userRepository,
                           SessionRepository sessionRepository,
                           PushNotificationService pushNotificationService,
                           BusinessContext businessContext) {
        this.presenceRepository = presenceRepository;
        this.userRepository = userRepository;
        this.sessionRepository = sessionRepository;
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
        if (hasEntryOn(user.getId(), type, day)) {
            throw new IllegalArgumentException(PresenceType.CLOCK_IN.equals(type)
                    ? "Kamu sudah absen masuk hari ini"
                    : "Kamu sudah absen keluar hari ini");
        }
        // You can't leave a shift you never started: a clock-out needs that day's clock-in.
        // Keyed on the clock-out's own day, so a clock-out just after midnight belongs to the
        // new day and needs a clock-in on it — the shift is per calendar day, not overnight.
        if (PresenceType.CLOCK_OUT.equals(type) && !hasEntryOn(user.getId(), PresenceType.CLOCK_IN, day)) {
            throw new IllegalArgumentException("Kamu belum absen masuk hari ini");
        }

        Presence presence = new Presence();
        presence.setBusinessId(user.getBusinessId());
        presence.setUserId(user.getId());
        presence.setUsername(user.getUsername());
        presence.setName(user.getName());
        presence.setType(type);
        presence.setClockDatetime(request.clockDatetime());
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
        pushAfterCommit(superAdmin.getUsername(), pushData(presence, title, CLOCK_PUSH_BODY),
                "presenceId=" + presence.getId());
    }

    /**
     * Super-admin spot-check: records one PING entry per targeted member and pushes it to all
     * of that member's devices. With no {@code userId} the whole team is pinged; with one,
     * only that member. Each member acknowledges their own entry via {@link #respond}.
     *
     * <p>Two things can keep a member out of it — <b>no registered device</b> (nothing could
     * ever reach them, and recording a PING would start their cooldown for a message they
     * can't receive) and the <b>{@link #PING_COOLDOWN}</b>. On a team-wide ping they're
     * skipped; on a targeted one they're a 400, because the admin asked for that person by
     * name and must be told it didn't happen. The result reports how many of the team were
     * actually reached, and who wasn't, so a partial ping never looks like a full one.
     */
    @Transactional
    public PingResultResponseDto ping(String callerUsername, PresencePingRequestDto request) {
        User caller = businessContext.requireUser(callerUsername);
        if (!caller.isSuperAdmin()) {
            throw new ForbiddenException("Hanya super admin yang dapat mengirim ping absen");
        }
        List<User> allMembers = userRepository.findByBusinessIdAndBusinessRoleAndDeletedAtIsNullOrderByCreatedAtAsc(
                caller.getBusinessId(), BusinessRole.MEMBER);
        if (allMembers.isEmpty()) {
            throw new IllegalArgumentException("Belum ada anggota tim yang dapat dikirimi ping");
        }

        Long targetUserId = request != null ? request.userId() : null;
        boolean targeted = targetUserId != null;
        List<User> candidates = targeted
                ? List.of(allMembers.stream()
                        .filter(m -> m.getId().equals(targetUserId))
                        .findFirst()
                        .orElseThrow(() -> new IllegalArgumentException("Anggota tim tidak ditemukan: " + targetUserId)))
                : allMembers;

        LocalDateTime sentAt = LocalDateTime.now();
        List<SkippedMemberDto> skipped = new ArrayList<>();
        List<User> targets = eligiblePingTargets(caller.getBusinessId(), candidates, targeted, sentAt, skipped);

        List<PresenceResponseDto> created = new ArrayList<>();
        for (User member : targets) {
            Presence presence = new Presence();
            presence.setBusinessId(caller.getBusinessId());
            presence.setUserId(member.getId());
            presence.setUsername(member.getUsername());
            presence.setName(member.getName());
            presence.setType(PresenceType.PING);
            presence.setClockDatetime(sentAt);
            // note + location stay null: they're the member's to fill in when they reply
            presenceRepository.save(presence);
            created.add(toDto(presence));

            // sendUrgentToUser fans out to every device the member has registered
            pushAfterCommit(member.getUsername(), pushData(presence, PING_PUSH_TITLE, PING_PUSH_BODY),
                    "presenceId=" + presence.getId());
        }
        log.info("[PRESENCE] PING sent to {}/{} member(s) of businessId={} by {} ({}), skipped={}",
                created.size(), allMembers.size(), caller.getBusinessId(), callerUsername,
                targeted ? "userId=" + targetUserId : "whole team", skipped.size());
        return new PingResultResponseDto(created.size(), allMembers.size(), skipped, created);
    }

    /**
     * Filters the ping candidates down to those that can actually be pinged — they have a
     * device registered for push, and they aren't inside the cooldown. Skipped members are
     * collected (with the reason) for the result; on a targeted ping the first reason instead
     * becomes a 400. If nothing survives, that's a 400 too rather than a silent no-op.
     */
    private List<User> eligiblePingTargets(Long businessId, List<User> candidates, boolean targeted,
                                           LocalDateTime now, List<SkippedMemberDto> skipped) {
        Set<String> withDevice = new HashSet<>(sessionRepository.findUsernamesWithPushToken(
                candidates.stream().map(User::getUsername).toList()));

        Map<Long, LocalDateTime> lastPingAt = new HashMap<>();
        for (Object[] row : presenceRepository.findLastPingAtByUserIds(
                businessId, candidates.stream().map(User::getId).toList())) {
            lastPingAt.put((Long) row[0], (LocalDateTime) row[1]);
        }

        List<User> eligible = new ArrayList<>();
        for (User member : candidates) {
            if (!withDevice.contains(member.getUsername())) {
                if (targeted) {
                    throw new IllegalArgumentException(member.getName()
                            + " belum memiliki perangkat yang terdaftar untuk notifikasi");
                }
                log.info("[PRESENCE] Skipping ping to username={} — no device registered", member.getUsername());
                skipped.add(skippedMember(member, SKIPPED_NO_DEVICE));
                continue;
            }

            LocalDateTime last = lastPingAt.get(member.getId());
            if (last == null || !last.isAfter(now.minus(PING_COOLDOWN))) {
                eligible.add(member);
                continue;
            }
            // Round the remaining wait UP: truncating would say "49 menit" with 49m59s left,
            // and a retry at that mark would just bounce again.
            long waitSeconds = Duration.between(now, last.plus(PING_COOLDOWN)).toSeconds();
            long waitMinutes = Math.max(1, (waitSeconds + 59) / 60);
            if (targeted) {
                throw new IllegalArgumentException(member.getName() + " baru saja di-ping. "
                        + "Coba lagi dalam " + waitMinutes + " menit");
            }
            log.info("[PRESENCE] Skipping ping to username={} — pinged {} (cooldown, {} min left)",
                    member.getUsername(), last, waitMinutes);
            skipped.add(skippedMember(member, SKIPPED_COOLDOWN));
        }
        if (eligible.isEmpty()) {
            throw new IllegalArgumentException(
                    "Tidak ada anggota tim yang dapat di-ping saat ini (tidak ada perangkat terdaftar "
                            + "atau semuanya baru saja di-ping)");
        }
        return eligible;
    }

    private SkippedMemberDto skippedMember(User member, String reason) {
        return new SkippedMemberDto(member.getId(), member.getUsername(), member.getName(), reason);
    }

    /**
     * The member taps the ping in the app: stamps {@code responseDatetime} (exactly once),
     * <b>where they were</b> when they answered, and an optional {@code note} — the reply the
     * super admin reads in the history. A PING is created with no location and no note; this
     * is what fills them.
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
        // A ping keeps for a day, then it's stale. A rolling 24h window (not "the same
        // calendar date") is what the member actually experiences — a ping sent at 23:00
        // stays answerable through the night instead of dying an hour later at midnight.
        LocalDateTime respondedAt = LocalDateTime.now();
        if (respondedAt.isAfter(presence.getClockDatetime().plus(PING_RESPONSE_WINDOW))) {
            throw new IllegalArgumentException(
                    "Ping ini sudah kedaluwarsa — hanya dapat direspons dalam 24 jam");
        }

        // Conditional update, not a read-then-write: two simultaneous taps (the member has the
        // app on several devices) both see a null responseDatetime above, but only one of them
        // updates a row here — the other is told it was already answered.
        if (presenceRepository.markResponded(presenceId, respondedAt, request.note(),
                request.latitude(), request.longitude()) == 0) {
            throw new IllegalArgumentException("Ping ini sudah direspons");
        }
        presence.setResponseDatetime(respondedAt);
        presence.setNote(request.note());
        presence.setLatitude(request.latitude());
        presence.setLongitude(request.longitude());
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

        LocalDateTime[] range = monthYearRange(year, month);
        Page<Presence> entries;
        if (range == null) {
            entries = scopedUserId == null
                    ? presenceRepository.findByBusinessIdOrderByClockDatetimeDescIdDesc(businessId, pageable)
                    : presenceRepository.findByBusinessIdAndUserIdOrderByClockDatetimeDescIdDesc(businessId, scopedUserId, pageable);
        } else {
            entries = scopedUserId == null
                    ? presenceRepository.findByBusinessIdAndClockDatetimeBetweenOrderByClockDatetimeDescIdDesc(
                            businessId, range[0], range[1], pageable)
                    : presenceRepository.findByBusinessIdAndUserIdAndClockDatetimeBetweenOrderByClockDatetimeDescIdDesc(
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
     * The {@code [from, to]} instants the period filter covers (inclusive of the last day's
     * final moment), or null for "no period filter". A {@code month} without a {@code year}
     * means that month of the <b>current</b> year. The range is over {@code clockDatetime} —
     * there is no stored date column to compare against any more.
     */
    private LocalDateTime[] monthYearRange(Integer year, Integer month) {
        if (year == null && month == null) return null;
        int y = year != null ? year : LocalDate.now().getYear();
        if (y < 2000 || y > 2100) {
            throw new IllegalArgumentException("year harus antara 2000 dan 2100");
        }
        if (month != null && (month < 1 || month > 12)) {
            throw new IllegalArgumentException("month harus antara 1 dan 12");
        }
        LocalDate firstDay = month == null ? LocalDate.of(y, 1, 1) : YearMonth.of(y, month).atDay(1);
        LocalDate lastDay = month == null ? LocalDate.of(y, 12, 31) : YearMonth.of(y, month).atEndOfMonth();
        return new LocalDateTime[]{firstDay.atStartOfDay(), endOfDay(lastDay)};
    }

    // Whole calendar day as a clock_datetime range, used by the period filter and the
    // once-per-day / clock-in-before-clock-out checks.
    private boolean hasEntryOn(Long userId, String type, LocalDate day) {
        return presenceRepository.existsByUserIdAndTypeAndClockDatetimeBetween(
                userId, type, day.atStartOfDay(), endOfDay(day));
    }

    private static LocalDateTime endOfDay(LocalDate day) {
        return day.atTime(LocalTime.MAX);
    }

    /**
     * Sends the push <b>only once the entry is actually committed</b>. Pushing inline would
     * fire while the transaction is still open: a later failure (the {@code uq_presence_clock_daily}
     * index catching a racing double tap, or one member's insert failing part-way through a
     * team-wide ping) rolls the row back, leaving a phone buzzing about an attendance entry
     * that doesn't exist — and, for a ping, a {@code presenceId} that 404s when tapped.
     *
     * <p>Still best-effort: a failure here is logged, never rethrown, so a dead FCM can't
     * fail a request whose data is already committed. Outside a transaction (tests calling
     * the service directly) it just sends immediately.
     */
    private void pushAfterCommit(String username, Map<String, String> data, String scope) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            sendQuietly(username, data, scope);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                sendQuietly(username, data, scope);
            }
        });
    }

    private void sendQuietly(String username, Map<String, String> data, String scope) {
        try {
            pushNotificationService.sendUrgentToUser(username, data);
        } catch (RuntimeException e) {
            log.error("[PRESENCE] Push failed for username={} {} (entry still recorded): {}",
                    username, scope, e.getMessage(), e);
        }
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
