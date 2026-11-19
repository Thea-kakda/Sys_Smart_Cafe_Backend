package com.smartcoffee.modules.inventory.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One batch/lot on the batch & expiry read surface (V038, M3). Carries the
 * ingredient labels, the lot/expiry, the on-hand quantity for that lot, its
 * cost, lifecycle status, and a {@code daysToExpiry} hint (negative = already
 * past expiry; null when the lot carries no expiry date). Returned by the
 * batch list and by a write-off.
 */
public record BatchExpiryResponse(UUID batchId, UUID storeId, UUID ingredientId, String ingredientCode,
        String ingredientName, String baseUnitCode, String lotNumber, LocalDate expiryDate, BigDecimal quantityBase,
        BigDecimal unitCost, String status, Integer daysToExpiry, Instant receivedAt, long version) {
}
