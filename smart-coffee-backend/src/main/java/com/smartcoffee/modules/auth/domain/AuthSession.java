package com.smartcoffee.modules.auth.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "identity", name = "auth_sessions")
public class AuthSession {
    @Id
    @Column(name = "session_id")
    private UUID id;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "token_family_id", nullable = false)
    private UUID tokenFamilyId;
    @Column(name = "refresh_token_hash", nullable = false, unique = true, length = 64)
    private String refreshTokenHash;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "revoked_at")
    private Instant revokedAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AuthSession() {
    }

    public AuthSession(UUID userId, UUID family, String hash, Instant expiry) {
        id = UUID.randomUUID();
        this.userId = userId;
        tokenFamilyId = family;
        refreshTokenHash = hash;
        expiresAt = expiry;
        createdAt = Instant.now();
    }

    public UUID userId() {
        return userId;
    }

    public UUID familyId() {
        return tokenFamilyId;
    }

    public boolean active() {
        return revokedAt == null && expiresAt.isAfter(Instant.now());
    }

    public void revoke() {
        revokedAt = Instant.now();
    }
}

