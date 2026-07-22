package com.bejo.core.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * A business (merchant). Many {@link User}s may belong to one business (flat role model);
 * all merchant-owned data — bank, QRIS, products, categories, transactions, stock
 * mutations, app settings — is scoped by {@code business_id}. The {@code picture} is the
 * single shared business image presented to all of its users. {@code nmid} is the
 * National Merchant ID used (later) to route inbound QRIS callbacks to the business.
 */
@Entity
@Table(name = "businesses")
public class Business {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String address;

    @Column(name = "nmid")
    private String nmid;

    // QRIS merchant PAN — the inbound callback carries this (not the NMID), so it routes a
    // payment callback to this business. Set optionally at registration.
    @Column(name = "merchant_pan")
    private String merchantPan;

    @Column(name = "initial_name")
    private String initialName;

    @Column(name = "initial_address", columnDefinition = "TEXT")
    private String initialAddress;

    @Column(name = "initial_nmid")
    private String initialNmid;

    // Set once at registration and never changed afterwards (validated against the
    // configurable app.business.sizes / app.business.industries option lists).
    @Column(nullable = false)
    private String size;

    @Column(nullable = false)
    private String industry;

    // Optional store photo (base64, compressed, <= 1 MB decoded — same spec as the picture)
    @Column(name = "store_photo", columnDefinition = "TEXT")
    private String storePhoto;

    // Shared business picture (base64, compressed, <= 1 MB decoded); null when unset
    @Column(columnDefinition = "TEXT")
    private String picture;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getAddress() { return address; }
    public void setAddress(String address) { this.address = address; }
    public String getNmid() { return nmid; }
    public void setNmid(String nmid) { this.nmid = nmid; }
    public String getMerchantPan() { return merchantPan; }
    public void setMerchantPan(String merchantPan) { this.merchantPan = merchantPan; }
    public String getInitialName() { return initialName; }
    public void setInitialName(String initialName) { this.initialName = initialName; }
    public String getInitialAddress() { return initialAddress; }
    public void setInitialAddress(String initialAddress) { this.initialAddress = initialAddress; }
    public String getInitialNmid() { return initialNmid; }
    public void setInitialNmid(String initialNmid) { this.initialNmid = initialNmid; }
    public String getSize() { return size; }
    public void setSize(String size) { this.size = size; }
    public String getIndustry() { return industry; }
    public void setIndustry(String industry) { this.industry = industry; }
    public String getStorePhoto() { return storePhoto; }
    public void setStorePhoto(String storePhoto) { this.storePhoto = storePhoto; }
    public String getPicture() { return picture; }
    public void setPicture(String picture) { this.picture = picture; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
