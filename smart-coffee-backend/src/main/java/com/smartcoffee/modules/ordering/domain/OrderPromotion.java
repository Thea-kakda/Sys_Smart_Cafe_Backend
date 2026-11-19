package com.smartcoffee.modules.ordering.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Promotion identity frozen onto an Order at checkout (Phase B2 §8): the
 * campaign code, name, type, value, eligible subtotal, applied discount and the
 * definition version the grant was evaluated against. Receipts and history read
 * this snapshot, never the live promotion row.
 */
public record OrderPromotion(UUID promotionId, String code, String name, String type, BigDecimal value,
        BigDecimal eligibleSubtotal, BigDecimal discountAmount, long version) {
}
