package com.smartcoffee.modules.inventory.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record PurchaseOrderResponse(UUID purchaseOrderId, String poNumber, UUID storeId, UUID supplierId,
        String supplierCode, String supplierName, String status, Instant orderedAt, Instant expectedAt, String notes,
        UUID createdByUserId, List<PurchaseOrderLineResponse> lines, Instant createdAt, Instant updatedAt,
        long version) {
}
