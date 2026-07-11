package com.bejo.entity;

import jakarta.persistence.*;
import java.util.UUID;

/** A business's QRIS image + code (one row per business, created on first upload). */
@Entity
@Table(name = "business_qris")
public class BusinessQris {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false, unique = true)
    private Long businessId;

    @Column(name = "qris_code", nullable = false, unique = true)
    private String qrisCode;

    @Column(name = "qris_image", columnDefinition = "TEXT")
    private String qrisImage;

    @Column(name = "initial_qris_image", columnDefinition = "TEXT")
    private String initialQrisImage;

    @PrePersist
    void prePersist() {
        if (qrisCode == null) qrisCode = UUID.randomUUID().toString();
    }

    public Long getId() { return id; }
    public Long getBusinessId() { return businessId; }
    public void setBusinessId(Long businessId) { this.businessId = businessId; }
    public String getQrisCode() { return qrisCode; }
    public void setQrisCode(String qrisCode) { this.qrisCode = qrisCode; }
    public String getQrisImage() { return qrisImage; }
    public void setQrisImage(String qrisImage) { this.qrisImage = qrisImage; }
    public String getInitialQrisImage() { return initialQrisImage; }
    public void setInitialQrisImage(String initialQrisImage) { this.initialQrisImage = initialQrisImage; }
}
