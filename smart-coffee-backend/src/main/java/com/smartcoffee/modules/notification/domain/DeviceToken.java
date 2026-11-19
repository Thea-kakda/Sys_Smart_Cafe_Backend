package com.smartcoffee.modules.notification.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "notification", name = "device_tokens")
public class DeviceToken {
    @Id
    @Column(name = "device_token_id")
    private UUID id;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;
    @Column(name = "encrypted_token", nullable = false, columnDefinition = "text")
    private String encryptedToken;
    @Column(nullable = false)
    private String platform;
    @Column(nullable = false)
    private boolean active;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected DeviceToken() {
    }

    public void deactivate() {
        active = false;
        updatedAt = Instant.now();
    }
}

