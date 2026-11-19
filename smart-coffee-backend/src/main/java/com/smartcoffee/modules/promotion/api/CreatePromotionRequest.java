package com.smartcoffee.modules.promotion.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Promotion creation payload. Field shape mirrors the persisted contract; all
 * rules (unique code, period, discount bounds, currency, limits, existing
 * targets) are enforced authoritatively in the application service, not here.
 * Nullable toggles/priority fall back to schema defaults (false/false/0).
 * Absent assignment lists mean "applies to everything" (no join rows), which is
 * exactly how the eligibility engine interprets empty assignment tables.
 */
public record CreatePromotionRequest(
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
        Boolean stackable,
        Boolean automatic,
        Integer priority,
        List<UUID> productIds,
        List<UUID> categoryIds,
        List<UUID> storeIds) {
}
