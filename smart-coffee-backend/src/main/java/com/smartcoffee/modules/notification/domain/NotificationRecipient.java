package com.smartcoffee.modules.notification.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(schema = "notification", name = "notification_recipients", uniqueConstraints = @UniqueConstraint(columnNames = {
        "notification_id", "user_id" }))
public class NotificationRecipient {
    @Id
    @Column(name = "notification_recipient_id")
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "notification_id", nullable = false)
    private Notification notification;
    @Column(name = "user_id", nullable = false)
    private UUID userId;
    @Column(nullable = false)
    private String locale;
    @Column(name = "read_at")
    private Instant readAt;
    @Column(name = "archived_at")
    private Instant archivedAt;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Version
    private long version;
    @OneToMany(mappedBy = "recipient", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<NotificationDelivery> deliveries = new ArrayList<>();

    protected NotificationRecipient() {
    }

    public NotificationRecipient(UUID user, String locale) {
        id = UUID.randomUUID();
        userId = user;
        this.locale = locale;
        createdAt = Instant.now();
    }

    void attach(Notification n) {
        notification = n;
    }

    public void addDelivery(NotificationDelivery d) {
        d.attach(this);
        deliveries.add(d);
    }

    public void read() {
        if (readAt == null)
            readAt = Instant.now();
    }

    public void archive() {
        if (archivedAt == null)
            archivedAt = Instant.now();
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public String locale() {
        return locale;
    }

    public Instant readAt() {
        return readAt;
    }

    public Instant archivedAt() {
        return archivedAt;
    }

    public Notification notification() {
        return notification;
    }
}

