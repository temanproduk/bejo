package com.bejo.entity;

import com.bejo.constant.PresenceType;
import jakarta.persistence.*;

import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * One employee-attendance entry: a member's clock-in / clock-out, or a PING the super
 * admin sent them. Scoped by {@code businessId}; {@code userId} is the member it belongs
 * to, with {@code username}/{@code name} snapshotted so history survives a later member
 * rename or soft-delete.
 *
 * <p>{@code clockDatetime} is the moment the entry is about (client-supplied for a
 * clock-in/out, the send time for a ping). Its calendar day is <b>derived</b>, never stored
 * — the month/year filter ranges over {@code clockDatetime}, and the once-per-day clock rule
 * is a unique index on {@code CAST(clock_datetime AS DATE)}. {@code responseDatetime} is only
 * set on a PING, when the member taps to acknowledge it.
 */
@Entity
@Table(name = "presences")
public class Presence {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    // Snapshots of the member at entry time (history outlives a rename / soft delete)
    @Column(nullable = false, length = 50)
    private String username;

    @Column(nullable = false)
    private String name;

    // CLOCK_IN | CLOCK_OUT | PING
    @Column(nullable = false)
    private String type = PresenceType.CLOCK_IN;

    @Column(name = "clock_datetime", nullable = false)
    private LocalDateTime clockDatetime;

    // Where the member clocked in/out. Null on a PING (the super admin sends no location).
    private Double latitude;

    private Double longitude;

    private String note;

    // PING only: when the member acknowledged it. Null = not responded (yet).
    @Column(name = "response_datetime")
    private LocalDateTime responseDatetime;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    /** The calendar day this entry falls on — derived, never stored (it's just the date part). */
    @Transient
    public LocalDate getPresenceDate() {
        return clockDatetime != null ? clockDatetime.toLocalDate() : null;
    }

    public Long getId() { return id; }
    public Long getBusinessId() { return businessId; }
    public void setBusinessId(Long businessId) { this.businessId = businessId; }
    public Long getUserId() { return userId; }
    public void setUserId(Long userId) { this.userId = userId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public LocalDateTime getClockDatetime() { return clockDatetime; }
    public void setClockDatetime(LocalDateTime clockDatetime) { this.clockDatetime = clockDatetime; }
    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }
    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }
    public LocalDateTime getResponseDatetime() { return responseDatetime; }
    public void setResponseDatetime(LocalDateTime responseDatetime) { this.responseDatetime = responseDatetime; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
