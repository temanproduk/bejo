package com.bejo.entity;

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

    @Column(name = "initial_name")
    private String initialName;

    @Column(name = "initial_address", columnDefinition = "TEXT")
    private String initialAddress;

    @Column(name = "initial_nmid")
    private String initialNmid;

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
    public String getInitialName() { return initialName; }
    public void setInitialName(String initialName) { this.initialName = initialName; }
    public String getInitialAddress() { return initialAddress; }
    public void setInitialAddress(String initialAddress) { this.initialAddress = initialAddress; }
    public String getInitialNmid() { return initialNmid; }
    public void setInitialNmid(String initialNmid) { this.initialNmid = initialNmid; }
    public String getPicture() { return picture; }
    public void setPicture(String picture) { this.picture = picture; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
