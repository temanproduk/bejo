package com.bejo.core.domain;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "token_blacklist", schema = "core")
public class TokenBlacklist {

    @Id
    private String jti;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    public String getJti() { return jti; }
    public void setJti(String jti) { this.jti = jti; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }
}
