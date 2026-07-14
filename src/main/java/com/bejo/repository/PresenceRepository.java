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
 */
@Repository
public interface PresenceRepository extends JpaRepository<Presence, Long> {

    Page<Presence> findByBusinessIdOrderByClockDatetimeDesc(Long businessId, Pageable pageable);

    Page<Presence> findByBusinessIdAndPresenceDateBetweenOrderByClockDatetimeDesc(
            Long businessId, LocalDate from, LocalDate to, Pageable pageable);

    Page<Presence> findByBusinessIdAndUserIdOrderByClockDatetimeDesc(
            Long businessId, Long userId, Pageable pageable);

    Page<Presence> findByBusinessIdAndUserIdAndPresenceDateBetweenOrderByClockDatetimeDesc(
            Long businessId, Long userId, LocalDate from, LocalDate to, Pageable pageable);

    Optional<Presence> findByIdAndBusinessId(Long id, Long businessId);

    // A member clocks in at most once and out at most once per calendar day
    boolean existsByUserIdAndTypeAndPresenceDate(Long userId, String type, LocalDate presenceDate);

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
