package com.bejo.entity;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "user_qris")
public class UserQris {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

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
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getQrisCode() { return qrisCode; }
    public void setQrisCode(String qrisCode) { this.qrisCode = qrisCode; }
    public String getQrisImage() { return qrisImage; }
    public void setQrisImage(String qrisImage) { this.qrisImage = qrisImage; }
    public String getInitialQrisImage() { return initialQrisImage; }
    public void setInitialQrisImage(String initialQrisImage) { this.initialQrisImage = initialQrisImage; }
}
