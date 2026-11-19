package com.smartcoffee.modules.inventory.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record GoodsReceiptResponse(UUID goodsReceiptId, String receiptNumber, UUID purchaseOrderId,
        String poNumber, UUID storeId, UUID receivedByUserId, Instant receivedAt,
        List<GoodsReceiptLineResponse> lines, long version) {
}
