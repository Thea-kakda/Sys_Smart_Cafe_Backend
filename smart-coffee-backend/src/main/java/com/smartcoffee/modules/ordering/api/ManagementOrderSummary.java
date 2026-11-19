package com.smartcoffee.modules.ordering.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Administrative order summary row (Master Prompt §8). Exposes operational and
 * financial facts an administrator needs, and deliberately omits customer personal
 * data (no name / email / phone) — only the opaque {@code customerId} is included,
 * which is required to correlate an order. Sourced from a single-table native read
 * over {@code ordering.orders}, so it never triggers the item/hardware graph load of
 * the customer-facing {@link OrderResponse}.
 */
public record ManagementOrderSummary(
        UUID orderId,
        String orderNumber,
        UUID storeId,
        UUID customerId,
        String source,
        String fulfillmentType,
        String status,
        String paymentStatus,
        String currencyCode,
        BigDecimal subtotal,
        BigDecimal discountAmount,
        BigDecimal taxAmount,
        BigDecimal deliveryFee,
        BigDecimal roundingAmount,
        BigDecimal totalAmount,
        int totalQuantity,
        Instant orderedAt,
        Instant updatedAt,
        long version) {
}
