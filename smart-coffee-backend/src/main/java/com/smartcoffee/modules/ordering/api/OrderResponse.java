package com.smartcoffee.modules.ordering.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

public record OrderResponse(UUID orderId, String orderNumber, String status, String paymentStatus, String currencyCode,
        BigDecimal subtotal, BigDecimal discountAmount, BigDecimal taxAmount, BigDecimal deliveryFee,
        BigDecimal roundingAmount, BigDecimal totalAmount, int totalQuantity, Instant orderedAt,
        List<OrderItemResponse> items, boolean idempotentReplay, UUID promotionId, String promotionCode,
        String promotionName, String promotionType, BigDecimal promotionValue, BigDecimal promotionEligibleSubtotal,
        BigDecimal promotionDiscountAmount, Long promotionVersion) {
}
