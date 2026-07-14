package com.bejo.repository;

import com.bejo.entity.Presence;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * The four finders below are the cross-product of the two optional list filters (member,
 * month-year) written out explicitly rather than as one query with nullable parameters —
 * a null-typed parameter in an {@code (:x IS NULL OR ...)} predicate is what forces
 * Postgres to guess the bind type.
 *
 * <p>They all order by {@code clockDatetime DESC, id DESC}. The {@code id} is not decoration:
 * a team-wide ping writes one row per member with the <b>identical</b> {@code clockDatetime},
 * and ordering by a non-unique column alone leaves tied rows in whatever order the database
 * feels like — across a page boundary that means an entry can show up twice, or not at all.
 * The id breaks every tie, so paging is stable.
 */
@Repository
public interface PresenceRepository extends JpaRepository<Presence, Long> {

    Page<Presence> findByBusinessIdOrderByClockDatetimeDescIdDesc(Long businessId, Pageable pageable);

    Page<Presence> findByBusinessIdAndClockDatetimeBetweenOrderByClockDatetimeDescIdDesc(
            Long businessId, LocalDateTime from, LocalDateTime to, Pageable pageable);

    Page<Presence> findByBusinessIdAndUserIdOrderByClockDatetimeDescIdDesc(
            Long businessId, Long userId, Pageable pageable);

    Page<Presence> findByBusinessIdAndUserIdAndClockDatetimeBetweenOrderByClockDatetimeDescIdDesc(
            Long businessId, Long userId, LocalDateTime from, LocalDateTime to, Pageable pageable);

    Optional<Presence> findByIdAndBusinessId(Long id, Long businessId);

    /**
     * Does this member already have an entry of this type on this calendar day? Backs both the
     * once-per-day clock rule and the "you must clock in before you clock out" check. The day
     * is a {@code [00:00, 23:59:59.999999999]} range over {@code clock_datetime} — there is no
     * stored date column to compare against (see {@code V3__presence_drop_presence_date.sql}).
     */
    boolean existsByUserIdAndTypeAndClockDatetimeBetween(
            Long userId, String type, LocalDateTime dayStart, LocalDateTime dayEnd);

    /**
     * When each of these members was last pinged — the ping cooldown reads this. One query for
     * the whole team (a per-member lookup would be an N+1 on a team-wide ping); members never
     * pinged are simply absent from the result. Each row is {@code [userId, lastPingedAt]}.
     */
    @Query("SELECT p.userId, MAX(p.clockDatetime) FROM Presence p "
            + "WHERE p.businessId = :businessId AND p.type = 'PING' AND p.userId IN :userIds "
            + "GROUP BY p.userId")
    List<Object[]> findLastPingAtByUserIds(@Param("businessId") Long businessId,
                                           @Param("userIds") List<Long> userIds);

    /**
     * Stamps a PING's response — the time, where the member was, and their optional note —
     * only if it hasn't been answered yet, and reports whether it did (0 = someone already
     * answered it). A read-then-write in the service would let two simultaneous taps both pass
     * the "not answered" check; this settles it in one statement, so a late second tap can't
     * overwrite the first reply's location or note either.
     */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE Presence p SET p.responseDatetime = :respondedAt, p.note = :note, "
            + "p.latitude = :latitude, p.longitude = :longitude "
            + "WHERE p.id = :id AND p.responseDatetime IS NULL")
    int markResponded(@Param("id") Long id,
                      @Param("respondedAt") LocalDateTime respondedAt,
                      @Param("note") String note,
                      @Param("latitude") Double latitude,
                      @Param("longitude") Double longitude);
}
