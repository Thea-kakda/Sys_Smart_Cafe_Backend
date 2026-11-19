package com.smartcoffee.infrastructure.messaging;

import tools.jackson.databind.JsonNode;

import java.time.Instant;
import java.util.UUID;

public record EventEnvelope(
        UUID eventId,
        String eventType,
        int eventVersion,
        String aggregateType,
        UUID aggregateId,
        UUID storeId,
        Instant occurredAt,
        UUID correlationId,
        UUID causationId,
        JsonNode payload
) {
    public EventEnvelope {
        if (eventId == null) {
            throw new IllegalArgumentException("eventId is required");
        }

        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType is required");
        }

        if (eventVersion <= 0) {
            throw new IllegalArgumentException(
                    "eventVersion must be greater than zero"
            );
        }

        if (aggregateType == null || aggregateType.isBlank()) {
            throw new IllegalArgumentException("aggregateType is required");
        }

        if (aggregateId == null) {
            throw new IllegalArgumentException("aggregateId is required");
        }

        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt is required");
        }

        if (payload == null) {
            throw new IllegalArgumentException("payload is required");
        }
    }
}
