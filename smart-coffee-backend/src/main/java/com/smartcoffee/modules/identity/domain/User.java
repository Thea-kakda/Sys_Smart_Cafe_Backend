package com.smartcoffee.modules.identity.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "identity", name = "users")
public class User {
    @Id
    @Column(name = "user_id")
    private UUID id;
    @Column(name = "full_name", nullable = false, length = 150)
    private String fullName;
    @Column(nullable = false, length = 320)
    private String email;
    @Column(name = "normalized_email", nullable = false, unique = true, length = 320)
    private String normalizedEmail;
    @Column(length = 30)
    private String phone;
    @Column(name = "preferred_language", nullable = false, length = 10)
    private String preferredLanguage;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private UserStatus status;
    @Column(name = "security_version", nullable = false)
    private long securityVersion;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    @Column(nullable = false)
    private long version;

    protected User() {
    }

    private User(UUID id, String name, String email, String normalized, String language) {
        this.id = id;
        this.fullName = name;
        this.email = email;
        this.normalizedEmail = normalized;
        this.preferredLanguage = language;
        this.status = UserStatus.ACTIVE;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public static User register(String name, String email, String language) {
        return new User(UUID.randomUUID(), name.trim(), email.trim(), email.trim().toLowerCase(), language);
    }

    public UUID id() {
        return id;
    }

    public String fullName() {
        return fullName;
    }

    public String email() {
        return email;
    }

    public String phone() {
        return phone;
    }

    public String preferredLanguage() {
        return preferredLanguage;
    }

    public UserStatus status() {
        return status;
    }

    public long securityVersion() {
        return securityVersion;
    }

    public long version() {
        return version;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public void changeStatus(UserStatus newStatus) {
        this.status = newStatus;
        this.updatedAt = Instant.now();
    }

    public void updateProfile(String name, String phone, String language) {
        this.fullName = name.trim();
        this.phone = phone;
        this.preferredLanguage = language;
        this.updatedAt = Instant.now();
    }

    /**
     * Invalidate all outstanding access tokens for this user. The JWT
     * authentication filter compares the token's {@code sec_ver} claim against
     * this column and rejects on mismatch. Called on logout-all; MUST also be
     * called by any future password-change or role-modification endpoint.
     */
    public void incrementSecurityVersion() {
        this.securityVersion++;
        this.updatedAt = Instant.now();
    }
}
