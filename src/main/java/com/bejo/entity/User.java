package com.bejo.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

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

    @Column(name = "initial_business_name")
    private String initialBusinessName;

    @Column(name = "initial_business_address")
    private String initialBusinessAddress;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String phone;

    @Column(name = "bank_name", nullable = false)
    private String bankName;

    @Column(name = "bank_number", nullable = false)
    private String bankNumber;

    @Column(name = "bank_account_name", nullable = false)
    private String bankAccountName;

    @Column(name = "initial_bank_name")
    private String initialBankName;

    @Column(name = "initial_bank_number")
    private String initialBankNumber;

    @Column(name = "initial_bank_account_name")
    private String initialBankAccountName;

    @Column(name = "qris_code", unique = true)
    private String qrisCode;

    @Column(name = "qris_image", columnDefinition = "TEXT")
    private String qrisImage;

    @Column(name = "uploaded_qris_image", columnDefinition = "TEXT")
    private String uploadedQrisImage;

    @Column(name = "is_email_verified", nullable = false)
    private boolean isEmailVerified = false;

    @Column(name = "is_phone_verified", nullable = false)
    private boolean isPhoneVerified = false;

    @Column(name = "account_type", nullable = false)
    private String accountType = "MANAGED";

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    void prePersist() {
        if (qrisCode == null) qrisCode = UUID.randomUUID().toString();
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
    public String getInitialBusinessName() { return initialBusinessName; }
    public void setInitialBusinessName(String initialBusinessName) { this.initialBusinessName = initialBusinessName; }
    public String getInitialBusinessAddress() { return initialBusinessAddress; }
    public void setInitialBusinessAddress(String initialBusinessAddress) { this.initialBusinessAddress = initialBusinessAddress; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getPhone() { return phone; }
    public void setPhone(String phone) { this.phone = phone; }
    public String getBankName() { return bankName; }
    public void setBankName(String bankName) { this.bankName = bankName; }
    public String getBankNumber() { return bankNumber; }
    public void setBankNumber(String bankNumber) { this.bankNumber = bankNumber; }
    public String getBankAccountName() { return bankAccountName; }
    public void setBankAccountName(String bankAccountName) { this.bankAccountName = bankAccountName; }
    public String getInitialBankName() { return initialBankName; }
    public void setInitialBankName(String initialBankName) { this.initialBankName = initialBankName; }
    public String getInitialBankNumber() { return initialBankNumber; }
    public void setInitialBankNumber(String initialBankNumber) { this.initialBankNumber = initialBankNumber; }
    public String getInitialBankAccountName() { return initialBankAccountName; }
    public void setInitialBankAccountName(String initialBankAccountName) { this.initialBankAccountName = initialBankAccountName; }
    public String getQrisCode() { return qrisCode; }
    public void setQrisCode(String qrisCode) { this.qrisCode = qrisCode; }
    public String getQrisImage() { return qrisImage; }
    public void setQrisImage(String qrisImage) { this.qrisImage = qrisImage; }
    public String getUploadedQrisImage() { return uploadedQrisImage; }
    public void setUploadedQrisImage(String uploadedQrisImage) { this.uploadedQrisImage = uploadedQrisImage; }
    public boolean isEmailVerified() { return isEmailVerified; }
    public void setEmailVerified(boolean emailVerified) { isEmailVerified = emailVerified; }
    public boolean isPhoneVerified() { return isPhoneVerified; }
    public void setPhoneVerified(boolean phoneVerified) { isPhoneVerified = phoneVerified; }
    public String getAccountType() { return accountType; }
    public void setAccountType(String accountType) { this.accountType = accountType; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
