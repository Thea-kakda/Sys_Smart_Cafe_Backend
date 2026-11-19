package com.smartcoffee.modules.inventory.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record StockTransferResponse(UUID stockTransferId, String transferNumber, UUID sourceStoreId,
        String sourceStoreName, UUID destStoreId, String destStoreName, String status, String notes,
        UUID requestedByUserId, Instant dispatchedAt, Instant receivedAt, List<StockTransferLineResponse> lines,
        Instant createdAt, Instant updatedAt, long version) {
}
