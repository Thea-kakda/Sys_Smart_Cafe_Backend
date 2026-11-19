package com.smartcoffee.modules.promotion.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Full administrative promotion view (Master Prompt §9). Every field maps to a
 * persisted column of promotion.promotions or its assignment join tables —
 * nothing is computed on the fly. Assignment lists are omitted (null) in
 * contexts where loading them would add no value; detail and mutation
 * responses always include them.
 */
public record PromotionAdminResponse(
        UUID promotionId,
        String promotionCode,
        String promotionName,
        String discountType,
        BigDecimal discountValue,
        BigDecimal minimumOrderAmount,
        BigDecimal maximumDiscountAmount,
        String currencyCode,
        Instant startsAt,
        Instant endsAt,
        String status,
        Integer usageLimit,
        Integer perCustomerLimit,
        int usageCount,
        boolean stackable,
        boolean automatic,
        int priority,
        long version,
        List<UUID> productIds,
        List<UUID> categoryIds,
        List<UUID> storeIds) {
}
