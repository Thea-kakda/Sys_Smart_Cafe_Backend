package com.smartcoffee.infrastructure.messaging.inbox;

import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
public class ConsumerInboxService {

    private final RaceSafeConsumerInboxService raceSafeService;

    public ConsumerInboxService(
            RaceSafeConsumerInboxService raceSafeService
    ) {
        this.raceSafeService = raceSafeService;
    }

    public boolean processOnce(
            String consumer,
            UUID eventId,
            String eventType,
            int eventVersion,
            Runnable action
    ) {
        return raceSafeService.processOnce(
                consumer,
                eventId,
                eventType,
                eventVersion,
                action
        );
    }
}
