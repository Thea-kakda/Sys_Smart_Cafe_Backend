package com.smartcoffee.modules.promotion.application;

import java.util.List;
import java.util.UUID;

/**
 * Server-owned input to promotion evaluation. It is constructed from a persisted
 * Cart (store, owned customer, currency and line snapshots), never from a public
 * request body (Phase B1 §3).
 */
public record PromotionQuoteContext(UUID storeId, UUID customerId, String currencyCode,
        List<PromotionQuoteLine> lines) {
    public PromotionQuoteContext {
        lines = lines == null ? List.of() : List.copyOf(lines);
    }
}
