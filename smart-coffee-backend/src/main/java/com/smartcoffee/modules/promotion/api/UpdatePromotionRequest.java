package com.smartcoffee.modules.promotion.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Promotion definition update. Full-replacement PUT semantics, mirroring the
 * established branch-management convention: name/discountType/discountValue/
 * startsAt/endsAt are required, while nullable columns (maximumDiscountAmount,
 * currencyCode, usageLimit, perCustomerLimit) are explicitly cleared when sent
 * as null. The promotion code and status are NOT editable here: codes are
 * immutable identity, status moves only through the dedicated /status endpoint
 * (explicit transition validation). usage_count is never accepted — historical
 * redemption totals cannot be rewritten through this API. Assignment lists are
 * full replacements when provided (empty list clears) and untouched when null.
 */
public record UpdatePromotionRequest(
        String promotionName,
        String discountType,
        BigDecimal discountValue,
        BigDecimal minimumOrderAmount,
        BigDecimal maximumDiscountAmount,
        String currencyCode,
        Instant startsAt,
        Instant endsAt,
        Integer usageLimit,
        Integer perCustomerLimit,
        Boolean stackable,
        Boolean automatic,
        Integer priority,
        List<UUID> productIds,
        List<UUID> categoryIds,
        List<UUID> storeIds,
        long version) {
}
