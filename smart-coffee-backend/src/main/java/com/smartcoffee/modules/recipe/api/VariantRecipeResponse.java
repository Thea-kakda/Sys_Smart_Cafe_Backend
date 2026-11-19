package com.smartcoffee.modules.recipe.api;

import java.util.UUID;

/**
 * Reflects a variant's recipe-override assignment state after a successful
 * assign/clear. {@code activeRecipeVersionId} is null once the override is
 * removed (the variant then falls back to the product's ACTIVE recipe).
 * {@code version} is the variant's new optimistic-lock value for the next echo.
 */
public record VariantRecipeResponse(
        UUID variantId,
        UUID productId,
        UUID activeRecipeVersionId,
        long version) {
}
