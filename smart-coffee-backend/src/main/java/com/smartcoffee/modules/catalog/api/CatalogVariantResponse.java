package com.smartcoffee.modules.catalog.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Availability-aware variant projection for the channel catalog (Slice 3B, spec §9).
 * Presents ONLY sellable-facing fields: identity, display name, price, the merged
 * orderability verdict and stable reason codes. It deliberately omits cost,
 * {@code recipeVersionId}, {@code resolutionSource} and raw serving counts, which
 * stay internal (Slice 3A variant results) and must never leak to POS/Customer.
 */
public record CatalogVariantResponse(
        UUID variantId,
        String code,
        String name,
        BigDecimal price,
        String currencyCode,
        boolean active,
        boolean orderable,
        boolean inStock,
        List<String> reasonCodes) {

    public CatalogVariantResponse {
        reasonCodes = List.copyOf(reasonCodes);
    }
}
