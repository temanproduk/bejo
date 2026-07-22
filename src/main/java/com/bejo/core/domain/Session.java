package com.bejo.core.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "bejo_sessions",
        uniqueConstraints = @UniqueConstraint(columnNames = {"username", "device_id"}))
public class Session {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String username;

    @Column(name = "login_date")
    private LocalDateTime loginDate;

    @Column(name = "login_status")
    private Boolean loginStatus;

    @Column(name = "push_token", columnDefinition = "TEXT")
    private String pushToken;

    @Column(name = "device_id", nullable = false)
    private String deviceId;

    @Column(name = "last_updated")
    private LocalDateTime lastUpdated;

    @Column(name = "schedule_start")
    private Integer scheduleStart;

    @Column(name = "schedule_end")
    private Integer scheduleEnd;

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public LocalDateTime getLoginDate() { return loginDate; }
    public void setLoginDate(LocalDateTime loginDate) { this.loginDate = loginDate; }
    public Boolean getLoginStatus() { return loginStatus; }
    public void setLoginStatus(Boolean loginStatus) { this.loginStatus = loginStatus; }
    public String getPushToken() { return pushToken; }
    public void setPushToken(String pushToken) { this.pushToken = pushToken; }
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String deviceId) { this.deviceId = deviceId; }
    public LocalDateTime getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(LocalDateTime lastUpdated) { this.lastUpdated = lastUpdated; }
    public Integer getScheduleStart() { return scheduleStart; }
    public void setScheduleStart(Integer scheduleStart) { this.scheduleStart = scheduleStart; }
    public Integer getScheduleEnd() { return scheduleEnd; }
    public void setScheduleEnd(Integer scheduleEnd) { this.scheduleEnd = scheduleEnd; }
}
