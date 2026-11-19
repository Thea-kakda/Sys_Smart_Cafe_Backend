package com.smartcoffee.modules.ordering.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Immutable promotion snapshot persisted on the order (Phase B2 §4/§13).
 * Administrative order history and receipts read this snapshot, never the live
 * promotion row, so editing a campaign cannot rewrite an existing order.
 */
public record ManagementPromotionSnapshot(
        UUID promotionId,
        String code,
        String name,
        String type,
        BigDecimal value,
        BigDecimal eligibleSubtotal,
        BigDecimal discountAmount,
        Long version) {
}
