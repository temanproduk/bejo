package com.bejo.repository;

import com.bejo.entity.AdDelivery;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;

/**
 * The device-driven transitions below are <b>conditional updates</b> (each carries a
 * {@code WHERE state = '…'} guard and returns the number of rows changed), not read-then-write.
 * The app may report the same step from several devices or retry after a dropped connection, so
 * exactly one report may move the row on; a later duplicate matches no row (returns 0) and the
 * service treats it as idempotent when the row is already in the target state.
 */
@Repository
public interface AdDeliveryRepository extends JpaRepository<AdDelivery, Long> {

    List<AdDelivery> findByCampaignId(Long campaignId);

    List<AdDelivery> findByCampaignIdIn(Collection<Long> campaignIds);

    /**
     * The merchant's ad history: every delivery to the business's devices, newest first. Scoped by
     * the {@code business_id} snapshotted on each row (so a delivery to a member removed since still
     * shows), backed by {@code idx_ad_deliveries_business (business_id, id DESC)}. The descending id
     * sort is stable across pages.
     */
    Page<AdDelivery> findByBusinessIdOrderByIdDesc(Long businessId, Pageable pageable);

    /** PREPARING → READY: the app reported a good download (size + hash matched). */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE AdDelivery d SET d.state = 'READY', d.readyAt = :at, "
            + "d.reportedSha256 = :sha, d.reportedSizeBytes = :size, "
            + "d.errorReason = null, d.erroredAt = null "
            + "WHERE d.id = :id AND d.state = 'PREPARING'")
    int markReady(@Param("id") Long id, @Param("at") LocalDateTime at,
                  @Param("sha") String sha, @Param("size") Long size);

    /** PREPARING → ERROR: the app reported the file missing or its hash/size didn't match. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE AdDelivery d SET d.state = 'ERROR', d.erroredAt = :at, d.errorReason = :reason, "
            + "d.reportedSha256 = :sha, d.reportedSizeBytes = :size "
            + "WHERE d.id = :id AND d.state = 'PREPARING'")
    int markError(@Param("id") Long id, @Param("at") LocalDateTime at, @Param("reason") String reason,
                  @Param("sha") String sha, @Param("size") Long size);

    /** STARTING → PLAYED: played, but no location reported yet (it may arrive later). */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE AdDelivery d SET d.state = 'PLAYED', d.playedAt = :at "
            + "WHERE d.id = :id AND d.state = 'STARTING'")
    int markPlayed(@Param("id") Long id, @Param("at") LocalDateTime at);

    /** STARTING → FINISHED: played and the location came in the same report. */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE AdDelivery d SET d.state = 'FINISHED', d.playedAt = :playedAt, d.finishedAt = :finishedAt, "
            + "d.playLatitude = :lat, d.playLongitude = :lng "
            + "WHERE d.id = :id AND d.state = 'STARTING'")
    int markPlayedAndFinished(@Param("id") Long id, @Param("playedAt") LocalDateTime playedAt,
                              @Param("finishedAt") LocalDateTime finishedAt,
                              @Param("lat") Double lat, @Param("lng") Double lng);

    /** PLAYED → FINISHED: the deferred location arrived on its own call (step 8). */
    @Modifying(clearAutomatically = true)
    @Query("UPDATE AdDelivery d SET d.state = 'FINISHED', d.finishedAt = :at, "
            + "d.playLatitude = :lat, d.playLongitude = :lng "
            + "WHERE d.id = :id AND d.state = 'PLAYED'")
    int markFinishedWithLocation(@Param("id") Long id, @Param("at") LocalDateTime at,
                                 @Param("lat") Double lat, @Param("lng") Double lng);
}
