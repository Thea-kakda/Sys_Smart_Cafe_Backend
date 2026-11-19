package com.smartcoffee.modules.availability;

import java.util.List;
import java.util.UUID;

/**
 * Availability of ONE ProductVariant (Slice 3A, spec §4/§9). Immutable.
 *
 * <p>Recipe resolution precedence (Variant override -> Product fallback -> NONE) is
 * reported through {@code resolutionSource} (VARIANT / PRODUCT / NONE) together with
 * the {@code recipeVersionId} actually used (nullable when NONE). {@code orderable}
 * means this variant's base/default configuration is in stock; {@code inStock} is the
 * pure inventory verdict for the requested quantity. {@code availableServings} is
 * nullable: non-null when inventory-limited, null when the variant is not
 * inventory-tracked (e.g. a legacy make-to-order item with no recipe, preserved
 * orderable per spec §6).
 */
public record VariantAvailabilityResult(
        UUID variantId,
        String variantName,
        boolean active,
        boolean orderable,
        boolean inStock,
        Integer availableServings,
        String resolutionSource,
        UUID recipeVersionId,
        List<String> reasonCodes) {

    public VariantAvailabilityResult {
        reasonCodes = List.copyOf(reasonCodes);
    }
}
