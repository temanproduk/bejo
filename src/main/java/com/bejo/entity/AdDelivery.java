package com.bejo.entity;

import com.bejo.constant.AdCampaignState;
import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * One delivery of an {@link AdCampaign} to one device — the row that carries the campaign's
 * lifecycle state (see {@link AdCampaignState}) and a timestamp for each step it passes through.
 * {@code targetUsername}/{@code targetDeviceId} name the {@code bejo_sessions} device to push
 * to; there is no FK (see {@code V4__ads.sql}). A campaign has exactly one delivery today; the
 * location-based campaign engine will insert several.
 */
@Entity
@Table(name = "ad_deliveries")
public class AdDelivery {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "campaign_id", nullable = false)
    private Long campaignId;

    // Snapshot of the target's business at send time — the merchant ad-history scope. Snapshotted
    // (not resolved from the username on read) so history survives the member being removed.
    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "target_username", nullable = false, length = 50)
    private String targetUsername;

    @Column(name = "target_device_id", nullable = false)
    private String targetDeviceId;

    @Column(nullable = false, length = 20)
    private String state = AdCampaignState.CREATED;

    @Column(name = "error_reason", length = 500)
    private String errorReason;

    // What the app echoed back on the download report (kept to audit a hash/size mismatch)
    @Column(name = "reported_sha256", length = 64)
    private String reportedSha256;

    @Column(name = "reported_size_bytes")
    private Long reportedSizeBytes;

    @Column(name = "prepared_at")
    private LocalDateTime preparedAt;

    @Column(name = "ready_at")
    private LocalDateTime readyAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "played_at")
    private LocalDateTime playedAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    @Column(name = "errored_at")
    private LocalDateTime erroredAt;

    // Where the ad played — may arrive after PLAYED (the app only reads location in foreground)
    @Column(name = "play_latitude")
    private Double playLatitude;

    @Column(name = "play_longitude")
    private Double playLongitude;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        if (state == null) state = AdCampaignState.CREATED;
    }

    public Long getId() { return id; }
    public Long getCampaignId() { return campaignId; }
    public void setCampaignId(Long campaignId) { this.campaignId = campaignId; }
    public Long getBusinessId() { return businessId; }
    public void setBusinessId(Long businessId) { this.businessId = businessId; }
    public String getTargetUsername() { return targetUsername; }
    public void setTargetUsername(String targetUsername) { this.targetUsername = targetUsername; }
    public String getTargetDeviceId() { return targetDeviceId; }
    public void setTargetDeviceId(String targetDeviceId) { this.targetDeviceId = targetDeviceId; }
    public String getState() { return state; }
    public void setState(String state) { this.state = state; }
    public String getErrorReason() { return errorReason; }
    public void setErrorReason(String errorReason) { this.errorReason = errorReason; }
    public String getReportedSha256() { return reportedSha256; }
    public void setReportedSha256(String reportedSha256) { this.reportedSha256 = reportedSha256; }
    public Long getReportedSizeBytes() { return reportedSizeBytes; }
    public void setReportedSizeBytes(Long reportedSizeBytes) { this.reportedSizeBytes = reportedSizeBytes; }
    public LocalDateTime getPreparedAt() { return preparedAt; }
    public void setPreparedAt(LocalDateTime preparedAt) { this.preparedAt = preparedAt; }
    public LocalDateTime getReadyAt() { return readyAt; }
    public void setReadyAt(LocalDateTime readyAt) { this.readyAt = readyAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getPlayedAt() { return playedAt; }
    public void setPlayedAt(LocalDateTime playedAt) { this.playedAt = playedAt; }
    public LocalDateTime getFinishedAt() { return finishedAt; }
    public void setFinishedAt(LocalDateTime finishedAt) { this.finishedAt = finishedAt; }
    public LocalDateTime getErroredAt() { return erroredAt; }
    public void setErroredAt(LocalDateTime erroredAt) { this.erroredAt = erroredAt; }
    public Double getPlayLatitude() { return playLatitude; }
    public void setPlayLatitude(Double playLatitude) { this.playLatitude = playLatitude; }
    public Double getPlayLongitude() { return playLongitude; }
    public void setPlayLongitude(Double playLongitude) { this.playLongitude = playLongitude; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
