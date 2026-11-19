package com.smartcoffee.modules.notification.domain;

import jakarta.persistence.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.Instant;
import java.util.*;

@Entity
@Table(schema = "notification", name = "notifications")
public class Notification {
    @Id
    @Column(name = "notification_id")
    private UUID id;
    @Column(name = "source_event_id")
    private UUID sourceEventId;
    @Column(name = "notification_type", nullable = false)
    private String type;
    @Column(nullable = false)
    private String category;
    @Column(name = "message_key", nullable = false)
    private String messageKey;
    // Template arguments JSON; bound as jsonb (see AuditLog, Checkpoint 2C).
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "template_arguments", nullable = false, columnDefinition = "jsonb")
    private String templateArguments;
    @Column(name = "action_url")
    private String actionUrl;
    @Column(name = "related_entity_type")
    private String relatedEntityType;
    @Column(name = "related_entity_id")
    private UUID relatedEntityId;
    @Column(nullable = false)
    private String priority;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "expires_at")
    private Instant expiresAt;
    @Version
    private long version;
    @OneToMany(mappedBy = "notification", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<NotificationRecipient> recipients = new ArrayList<>();

    protected Notification() {
    }

    public static Notification create(UUID event, String type, String category, String key, String args, String url,
            String entityType, UUID entityId, String priority, Instant expiry) {
        var n = new Notification();
        n.id = UUID.randomUUID();
        n.sourceEventId = event;
        n.type = type;
        n.category = category;
        n.messageKey = key;
        n.templateArguments = args;
        n.actionUrl = url;
        n.relatedEntityType = entityType;
        n.relatedEntityId = entityId;
        n.priority = priority;
        n.createdAt = Instant.now();
        n.expiresAt = expiry;
        return n;
    }

    public void addRecipient(NotificationRecipient r) {
        r.attach(this);
        recipients.add(r);
    }

    public UUID id() {
        return id;
    }

    public String type() {
        return type;
    }

    public String category() {
        return category;
    }

    public String messageKey() {
        return messageKey;
    }

    public String templateArguments() {
        return templateArguments;
    }

    public String actionUrl() {
        return actionUrl;
    }

    public String priority() {
        return priority;
    }

    public Instant createdAt() {
        return createdAt;
    }
}

