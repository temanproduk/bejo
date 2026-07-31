package com.bejo.core.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * A business's shared QRIS — one row per business, created on first upload.
 *
 * <p>Deliberately the same shape as {@link UserQris} (which only adds {@code username}), so the
 * shared QRIS and a per-user one behave identically everywhere. {@code qrisCode} is the EMV
 * payload the app decoded from the merchant's QR and re-renders locally; the merchant identity
 * beside it is parsed out of that code at upload, so the caption always describes the QR it labels.
 */
@Entity
@Table(name = "business_qris", schema = "core")
public class BusinessQris {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false, unique = true)
    private Long businessId;

    // The QRIS terminal; an inbound payment callback routes on it.
    @Column(name = "terminal_id", length = 50)
    private String terminalId;

    @Column(name = "qris_code", columnDefinition = "TEXT", nullable = false)
    private String qrisCode;

    @Column(name = "merchant_name", length = 255)
    private String merchantName;

    @Column(name = "nmid", length = 50)
    private String nmid;

    @Column(name = "acquirer", length = 100)
    private String acquirer;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public Long getBusinessId() { return businessId; }
    public void setBusinessId(Long businessId) { this.businessId = businessId; }
    public String getTerminalId() { return terminalId; }
    public void setTerminalId(String terminalId) { this.terminalId = terminalId; }
    public String getQrisCode() { return qrisCode; }
    public void setQrisCode(String qrisCode) { this.qrisCode = qrisCode; }
    public String getMerchantName() { return merchantName; }
    public void setMerchantName(String merchantName) { this.merchantName = merchantName; }
    public String getNmid() { return nmid; }
    public void setNmid(String nmid) { this.nmid = nmid; }
    public String getAcquirer() { return acquirer; }
    public void setAcquirer(String acquirer) { this.acquirer = acquirer; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
}
