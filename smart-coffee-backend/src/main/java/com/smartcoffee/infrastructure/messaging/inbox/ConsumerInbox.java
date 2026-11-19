package com.smartcoffee.infrastructure.messaging.inbox;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "integration", name = "consumer_inbox", uniqueConstraints = @UniqueConstraint(name = "uk_consumer_event", columnNames = {
        "consumer_name", "event_id" }))
public class ConsumerInbox {
    @Id
    @Column(name = "inbox_id")
    private UUID id = UUID.randomUUID();
    @Column(name = "consumer_name", nullable = false)
    private String consumerName;
    @Column(name = "event_id", nullable = false)
    private UUID eventId;
    @Column(name = "event_type", nullable = false)
    private String eventType;
    @Column(name = "event_version", nullable = false)
    private int eventVersion;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InboxStatus status = InboxStatus.RECEIVED;
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();
    @Column(name = "processed_at")
    private Instant processedAt;
    @Version
    private long version;

    protected ConsumerInbox() {
    }

    public ConsumerInbox(String name, UUID eventId, String eventType, int version) {
        this.consumerName = name;
        this.eventId = eventId;
        this.eventType = eventType;
        this.eventVersion = version;
    }

    public void processed() {
        status = InboxStatus.PROCESSED;
        processedAt = Instant.now();
    }
}

