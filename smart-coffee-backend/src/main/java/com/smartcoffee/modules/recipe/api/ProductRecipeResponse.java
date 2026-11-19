package com.smartcoffee.modules.recipe.api;

import java.util.UUID;

/**
 * Reflects a product's fallback-recipe assignment state after a successful
 * assign/clear (Slice 2B.1). {@code activeRecipeVersionId} is null once the
 * fallback is removed. {@code version} is the product's new optimistic-lock
 * value for the next echo.
 */
public record ProductRecipeResponse(
        UUID productId,
        UUID activeRecipeVersionId,
        long version) {
}
