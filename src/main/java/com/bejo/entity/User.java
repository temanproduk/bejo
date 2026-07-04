package com.bejo.entity;

import com.bejo.constant.AccountType;
import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "users")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String name;

    @Column(name = "business_name", nullable = false)
    private String businessName;

    @Column(name = "business_address")
    private String businessAddress;

    @Column(name = "nmid")
    private String nmid;

    @Column(name = "initial_business_name")
    private String initialBusinessName;

    @Column(name = "initial_business_address")
    private String initialBusinessAddress;

    @Column(name = "initial_nmid")
    private String initialNmid;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String phone;

    @Column(name = "is_email_verified", nullable = false)
    private boolean isEmailVerified = false;

    @Column(name = "is_phone_verified", nullable = false)
    private boolean isPhoneVerified = false;

    @Column(name = "account_type", nullable = false)
    private String accountType = AccountType.MANAGED;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (createdAt == null) createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getBusinessName() { return businessName; }
    public void setBusinessName(String businessName) { this.businessName = businessName; }
    public String getBusinessAddress() { return businessAddress; }
    public void setBusinessAddress(String businessAddress) { this.businessAddress = businessAddress; }
    public String getNmid() { return nmid; }
    public void setNmid(String nmid) { this.nmid = nmid; }
    public String getInitialBusinessName() { return initialBusinessName; }
    public void setInitialBusinessName(String v) { this.initialBusinessName = v; }
    public String getInitialBusinessAddress() { return initialBusinessAddress; }
    public void setInitialBusinessAddress(String v) { this.initialBusinessAddress = v; }
    public String getInitialNmid() { return initialNmid; }
    public void setInitialNmid(String initialNmid) { this.initialNmid = initialNmid; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public boolean isEmailVerified() { return isEmailVerified; }
    public void setEmailVerified(boolean emailVerified) { isEmailVerified = emailVerified; }
    public boolean isPhoneVerified() { return isPhoneVerified; }
    public void setPhoneVerified(boolean phoneVerified) { isPhoneVerified = phoneVerified; }
    public String getAccountType() { return accountType; }
    public void setAccountType(String accountType) { this.accountType = accountType; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
