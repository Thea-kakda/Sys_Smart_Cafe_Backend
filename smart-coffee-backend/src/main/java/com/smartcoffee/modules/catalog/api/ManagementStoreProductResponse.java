package com.smartcoffee.modules.catalog.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Authoritative branch-product management row (Checkpoint 3A/3B). Field
 * semantics are fixed by the backend:
 * <ul>
 * <li>{@code inheritedPrice} — true when {@code overridePrice} is null;</li>
 * <li>{@code effectivePrice} — overridePrice when non-null, else basePrice;</li>
 * <li>{@code effectiveAvailable} — the existing verified rule
 * {@code branchAvailable AND no active sold-out window}, identical to the
 * public catalog projection;</li>
 * <li>{@code productVersion}/{@code storeProductVersion} — optimistic-lock
 * values the client must echo on update.</li>
 * </ul>
 * The frontend displays these values and never recomputes them.
 */
public record ManagementStoreProductResponse(
        UUID productId,
        String productCode,
        String productName,
        UUID categoryId,
        String categoryName,
        BigDecimal basePrice,
        BigDecimal overridePrice,
        boolean inheritedPrice,
        BigDecimal effectivePrice,
        String currencyCode,
        boolean productActive,
        boolean branchAvailable,
        boolean effectiveAvailable,
        Instant soldOutUntil,
        String stationCode,
        int preparationMinutes,
        boolean customizable,
        long productVersion,
        long storeProductVersion,
        Instant updatedAt) {
}
