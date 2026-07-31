package com.bejo.core.domain;

import jakarta.persistence.*;

import java.time.LocalDateTime;

/**
 * A single user's own QRIS image + terminal id, set by the business's main account. Same merchant
 * PAN as the shared {@link BusinessQris}, but a distinct terminal id so a payment callback (which
 * carries the terminal id) can be routed to just this user. A user without a row here falls back to
 * the shared business QRIS and is notified for every callback of the merchant.
 */
@Entity
@Table(name = "user_qris", schema = "core")
public class UserQris {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(nullable = false, length = 50)
    private String username;

    @Column(name = "terminal_id", nullable = false, length = 50)
    private String terminalId;

    // The user's own QRIS code (EMV payload). Column name is legacy — it used to hold a base64
    // image; it now stores the code the app renders locally. Kept so this needs no migration.
    @Column(name = "qris_image", columnDefinition = "TEXT", nullable = false)
    private String qrisPayload;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getBusinessId() { return businessId; }
    public void setBusinessId(Long businessId) { this.businessId = businessId; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getTerminalId() { return terminalId; }
    public void setTerminalId(String terminalId) { this.terminalId = terminalId; }
    public String getQrisPayload() { return qrisPayload; }
    public void setQrisPayload(String qrisPayload) { this.qrisPayload = qrisPayload; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
