package com.smartcoffee.infrastructure.messaging.outbox;

import java.time.Instant;
import java.util.*;

public interface OutboxClaimRepository {
    List<OutboxEvent> claim(String owner, int batchSize, Instant staleBefore);

    void markPublished(UUID id);

    void markRetry(UUID id, String errorCode);
}

