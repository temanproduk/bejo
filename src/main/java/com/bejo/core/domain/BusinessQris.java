package com.bejo.core.domain;

import jakarta.persistence.*;
import java.util.UUID;

/**
 * A business's QRIS (one row per business, created on first upload).
 *
 * <p><b>Column naming is legacy.</b> {@code qris_image} no longer holds a base64 image: it now
 * stores the <b>QRIS code</b> — the EMV payload string the app decodes from the merchant's QR
 * and re-renders locally, instead of the server shipping a multi-MB image on every read. The
 * column keeps its old name so the change needs no migration; the field is {@code qrisPayload}
 * and the API exposes it as {@code qrisCode}.
 *
 * <p>Unrelated: {@code qris_code} / {@link #getQrisCode()} is a vestigial internal UUID.
 */
@Entity
@Table(name = "business_qris", schema = "core")
public class BusinessQris {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_id", nullable = false, unique = true)
    private Long businessId;

    @Column(name = "qris_code", nullable = false, unique = true)
    private String qrisCode;

    // The QRIS code (EMV payload). Column name is legacy — see the class javadoc.
    @Column(name = "qris_image", columnDefinition = "TEXT")
    private String qrisPayload;

    // The managed/initial QRIS served when the merchant hasn't supplied their own.
    @Column(name = "initial_qris_image", columnDefinition = "TEXT")
    private String initialQrisPayload;

    @PrePersist
    void prePersist() {
        if (qrisCode == null) qrisCode = UUID.randomUUID().toString();
    }

    public Long getId() { return id; }
    public Long getBusinessId() { return businessId; }
    public void setBusinessId(Long businessId) { this.businessId = businessId; }
    public String getQrisCode() { return qrisCode; }
    public void setQrisCode(String qrisCode) { this.qrisCode = qrisCode; }
    public String getQrisPayload() { return qrisPayload; }
    public void setQrisPayload(String qrisPayload) { this.qrisPayload = qrisPayload; }
    public String getInitialQrisPayload() { return initialQrisPayload; }
    public void setInitialQrisPayload(String initialQrisPayload) { this.initialQrisPayload = initialQrisPayload; }
}
