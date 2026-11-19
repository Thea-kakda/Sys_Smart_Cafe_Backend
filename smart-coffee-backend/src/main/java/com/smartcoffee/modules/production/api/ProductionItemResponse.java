package com.smartcoffee.modules.production.api;

import java.time.Instant;
import java.util.UUID;

public record ProductionItemResponse(UUID ticketItemId, UUID orderItemId, String productName, int quantity,
        String customizationSummary, String status, Instant startedAt, Instant readyAt, long version) {
}

