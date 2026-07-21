package com.bejo.entity;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * A voice-ad campaign: a brand's ≤10s audio clip. The bytes live in Cloud Storage; this row
 * keeps only the object name and the integrity facts ({@code audioSha256}/{@code audioSizeBytes})
 * the device must match after downloading. A campaign is delivered to devices as
 * {@link AdDelivery} rows — the lifecycle state lives there, not here.
 */
@Entity
@Table(name = "ad_campaigns")
public class AdCampaign {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    private String brand;

    // GCS object name (the bucket is app config, not stored per row)
    @Column(name = "audio_object", nullable = false, length = 500)
    private String audioObject;

    @Column(name = "audio_content_type", nullable = false, length = 100)
    private String audioContentType;

    @Column(name = "audio_sha256", nullable = false, length = 64)
    private String audioSha256;

    @Column(name = "audio_size_bytes", nullable = false)
    private Long audioSizeBytes;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBrand() { return brand; }
    public void setBrand(String brand) { this.brand = brand; }
    public String getAudioObject() { return audioObject; }
    public void setAudioObject(String audioObject) { this.audioObject = audioObject; }
    public String getAudioContentType() { return audioContentType; }
    public void setAudioContentType(String audioContentType) { this.audioContentType = audioContentType; }
    public String getAudioSha256() { return audioSha256; }
    public void setAudioSha256(String audioSha256) { this.audioSha256 = audioSha256; }
    public Long getAudioSizeBytes() { return audioSizeBytes; }
    public void setAudioSizeBytes(Long audioSizeBytes) { this.audioSizeBytes = audioSizeBytes; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
