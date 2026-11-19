package com.smartcoffee.infrastructure.messaging.outbox;

import com.smartcoffee.infrastructure.messaging.EventEnvelope;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "integration", name = "outbox_events")
public class OutboxEvent {

    @Id
    @Column(name = "outbox_event_id", nullable = false, updatable = false)
    private UUID id = UUID.randomUUID();

    @Column(name = "event_id", nullable = false, unique = true, updatable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 150, updatable = false)
    private String eventType;

    @Column(name = "event_version", nullable = false, updatable = false)
    private int eventVersion;

    @Column(name = "aggregate_type", nullable = false, length = 100, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private UUID aggregateId;

    @Column(name = "routing_key", nullable = false, length = 200, updatable = false)
    private String routingKey;

    // Event payload JSON; bound as jsonb (see AuditLog, Checkpoint 2C).
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false, updatable = false)
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private OutboxStatus status;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    @Column(name = "available_at", nullable = false)
    private Instant availableAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "attempt_count", nullable = false)
    private int attemptCount;

    @Version
    @Column(name = "version", nullable = false)
    private long version;

    protected OutboxEvent() {
    }

    public OutboxEvent(
            EventEnvelope envelope,
            String routingKey,
            String payload
    ) {
        if (envelope == null) {
            throw new IllegalArgumentException("Event envelope is required");
        }

        if (routingKey == null || routingKey.isBlank()) {
            throw new IllegalArgumentException("Routing key is required");
        }

        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("Payload is required");
        }

        this.eventId = envelope.eventId();
        this.eventType = envelope.eventType();
        this.eventVersion = envelope.eventVersion();
        this.aggregateType = envelope.aggregateType();
        this.aggregateId = envelope.aggregateId();
        this.routingKey = routingKey;
        this.payload = payload;
        this.status = OutboxStatus.PENDING;
        this.occurredAt = envelope.occurredAt();
        this.availableAt = Instant.now();
        this.attemptCount = 0;
    }

    public static OutboxEvent rehydrate(
            UUID id,
            UUID eventId,
            String eventType,
            int eventVersion,
            String aggregateType,
            UUID aggregateId,
            String routingKey,
            String payload,
            OutboxStatus status,
            Instant occurredAt,
            Instant availableAt,
            Instant publishedAt,
            int attemptCount,
            long version
    ) {
        OutboxEvent event = new OutboxEvent();

        event.id = id;
        event.eventId = eventId;
        event.eventType = eventType;
        event.eventVersion = eventVersion;
        event.aggregateType = aggregateType;
        event.aggregateId = aggregateId;
        event.routingKey = routingKey;
        event.payload = payload;
        event.status = status;
        event.occurredAt = occurredAt;
        event.availableAt = availableAt;
        event.publishedAt = publishedAt;
        event.attemptCount = attemptCount;
        event.version = version;

        return event;
    }

    public UUID id() {
        return id;
    }

    public UUID eventId() {
        return eventId;
    }

    public String eventType() {
        return eventType;
    }

    public int eventVersion() {
        return eventVersion;
    }

    public String aggregateType() {
        return aggregateType;
    }

    public UUID aggregateId() {
        return aggregateId;
    }

    public String routingKey() {
        return routingKey;
    }

    public String payload() {
        return payload;
    }

    public OutboxStatus status() {
        return status;
    }

    public Instant occurredAt() {
        return occurredAt;
    }

    public Instant availableAt() {
        return availableAt;
    }

    public Instant publishedAt() {
        return publishedAt;
    }

    public int attemptCount() {
        return attemptCount;
    }

    public long version() {
        return version;
    }

    public void markProcessing() {
        if (status != OutboxStatus.PENDING && status != OutboxStatus.FAILED) {
            throw new IllegalStateException(
                    "Only PENDING or FAILED outbox events can be processed"
            );
        }

        status = OutboxStatus.PROCESSING;
    }

    public void published() {
        status = OutboxStatus.PUBLISHED;
        publishedAt = Instant.now();
    }

    public void retry() {
        status = OutboxStatus.PENDING;
        publishedAt = null;
        attemptCount++;

        long delaySeconds = Math.min(
                300L,
                1L << Math.min(attemptCount, 8)
        );

        availableAt = Instant.now().plusSeconds(delaySeconds);
    }
}
