package com.smartcoffee.modules.notification.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "notification", name = "notification_deliveries", uniqueConstraints = @UniqueConstraint(columnNames = {
        "notification_recipient_id", "channel" }))
public class NotificationDelivery {
    @Id
    @Column(name = "notification_delivery_id")
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "notification_recipient_id", nullable = false)
    private NotificationRecipient recipient;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeliveryChannel channel;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DeliveryStatus status;
    @Column(name = "provider_name")
    private String providerName;
    @Column(name = "provider_reference")
    private String providerReference;
    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;
    @Column(name = "next_attempt_at")
    private Instant nextAttemptAt;
    @Column(name = "delivered_at")
    private Instant deliveredAt;
    @Column(name = "last_error_code")
    private String lastErrorCode;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected NotificationDelivery() {
    }

    public NotificationDelivery(DeliveryChannel channel) {
        id = UUID.randomUUID();
        this.channel = channel;
        status = channel == DeliveryChannel.IN_APP ? DeliveryStatus.DELIVERED : DeliveryStatus.PENDING;
        createdAt = updatedAt = Instant.now();
        if (status == DeliveryStatus.DELIVERED)
            deliveredAt = createdAt;
    }

    void attach(NotificationRecipient r) {
        recipient = r;
    }

    public void delivered(String provider, String ref) {
        status = DeliveryStatus.DELIVERED;
        providerName = provider;
        providerReference = ref;
        deliveredAt = updatedAt = Instant.now();
    }

    public void failed(String code) {
        status = DeliveryStatus.PENDING;
        lastErrorCode = code;
        attemptCount++;
        nextAttemptAt = Instant.now().plusSeconds(Math.min(3600, 1L << Math.min(attemptCount, 11)));
        updatedAt = Instant.now();
    }

    public UUID id() {
        return id;
    }

    public DeliveryChannel channel() {
        return channel;
    }

    public DeliveryStatus status() {
        return status;
    }

    public int attemptCount() {
        return attemptCount;
    }

    public Instant nextAttemptAt() {
        return nextAttemptAt;
    }

    public NotificationRecipient recipient() {
        return recipient;
    }
}

