package com.smartcoffee.modules.notification.domain;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(schema = "notification", name = "notification_preferences", uniqueConstraints = @UniqueConstraint(columnNames = {
        "user_id", "category", "channel" }))
public class NotificationPreference {
    @Id
    @Column(name = "notification_preference_id")
    private UUID id;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(nullable = false)
    private String category;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeliveryChannel channel;
    @Column(nullable = false)
    private boolean enabled;
    @Version
    private long version;

    protected NotificationPreference() {
    }

    public UUID userId() {
        return userId;
    }

    public String category() {
        return category;
    }

    public DeliveryChannel channel() {
        return channel;
    }

    public boolean enabled() {
        return enabled;
    }
}

