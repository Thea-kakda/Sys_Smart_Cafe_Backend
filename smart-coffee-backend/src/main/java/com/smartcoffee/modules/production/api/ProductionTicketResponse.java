package com.smartcoffee.modules.production.api;

import java.time.Instant;
import java.util.*;

public record ProductionTicketResponse(UUID ticketId, String ticketNumber, UUID orderId, UUID storeId, UUID stationId,
        String stationType, String status, String priority, Instant queuedAt, List<ProductionItemResponse> items,
        long version) {
}

