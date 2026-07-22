package com.bejo.core.domain;

import com.bejo.core.domain.BusinessRole;
import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * A user account. Identity + credentials only — business info (name, bank, QRIS, picture)
 * lives on {@link Business}, which many users may share. Each user belongs to exactly one
 * business ({@code businessId}) with a {@link BusinessRole}. Soft-deletable: a non-null
 * {@code deletedAt} means the account is removed and its {@code username} is released for
 * reuse (uniqueness is enforced only among active users).
 */
@Entity
@Table(name = "users", schema = "core")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    // Not DB-unique: a soft-deleted user keeps its row but its username is reusable.
    // Uniqueness among ACTIVE users is enforced in-app (+ a partial unique index in Postgres).
    @Column(nullable = false, length = 50)
    private String username;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Column(nullable = false)
    private String name;

    @Column(name = "business_id", nullable = false)
    private Long businessId;

    @Column(name = "business_role", nullable = false)
    private String businessRole = BusinessRole.MEMBER;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String phone;

    @Column(name = "is_email_verified", nullable = false)
    private boolean isEmailVerified = false;

    @Column(name = "is_phone_verified", nullable = false)
    private boolean isPhoneVerified = false;

    @Column(name = "account_type", nullable = false)
    private String accountType = com.bejo.core.domain.AccountType.MANAGED;

    // Soft delete: NULL = active, non-NULL = when the account was removed
    @Column(name = "deleted_at")
    private LocalDateTime deletedAt;

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
    public Long getBusinessId() { return businessId; }
    public void setBusinessId(Long businessId) { this.businessId = businessId; }
    public String getBusinessRole() { return businessRole; }
    public void setBusinessRole(String businessRole) { this.businessRole = businessRole; }
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
    public LocalDateTime getDeletedAt() { return deletedAt; }
    public void setDeletedAt(LocalDateTime deletedAt) { this.deletedAt = deletedAt; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public boolean isSuperAdmin() { return BusinessRole.SUPER_ADMIN.equals(businessRole); }
    public boolean isDeleted() { return deletedAt != null; }
}
