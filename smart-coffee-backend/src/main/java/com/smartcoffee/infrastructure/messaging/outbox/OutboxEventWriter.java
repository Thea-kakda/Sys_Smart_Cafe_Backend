package com.smartcoffee.infrastructure.messaging.outbox;

import com.smartcoffee.infrastructure.messaging.*;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxEventWriter {
    private final OutboxEventRepository repo;
    private final EventSerializer serializer;

    public OutboxEventWriter(OutboxEventRepository repo, EventSerializer serializer) {
        this.repo = repo;
        this.serializer = serializer;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void append(EventEnvelope event, String routingKey) {
        repo.save(new OutboxEvent(event, routingKey, serializer.serialize(event)));
    }
}

