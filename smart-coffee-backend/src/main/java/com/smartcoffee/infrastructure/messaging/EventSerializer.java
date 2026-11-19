package com.smartcoffee.infrastructure.messaging;

import tools.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

@Component
public class EventSerializer {
    private final ObjectMapper mapper;

    public EventSerializer(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String serialize(EventEnvelope event) {
        try {
            return mapper.writeValueAsString(event);
        } catch (Exception e) {
            throw new IllegalStateException("Cannot serialize event", e);
        }
    }
}

