package com.smartcoffee.infrastructure.messaging.deadletter;

import org.slf4j.*;
import org.springframework.stereotype.Service;

@Service
public class DeadLetterService {
    private static final Logger log = LoggerFactory.getLogger(DeadLetterService.class);

    public void record(String eventId, String code) {
        log.error("Dead-letter eventId={} code={}", eventId, code);
    }
}

