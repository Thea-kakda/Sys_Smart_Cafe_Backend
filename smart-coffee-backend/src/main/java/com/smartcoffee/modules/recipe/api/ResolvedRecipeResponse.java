package com.smartcoffee.modules.recipe.api;

import com.smartcoffee.modules.recipe.application.RecipeVersionResolver.Source;
import java.util.UUID;

/**
 * Result of resolving the governing recipe for a product (optionally through a
 * variant override). {@code source} reports which rule fired — VARIANT, PRODUCT
 * or NONE — and {@code recipeVersion} is null when {@code source == NONE}.
 */
public record ResolvedRecipeResponse(
        UUID productId,
        UUID variantId,
        Source source,
        RecipeVersionResponse recipeVersion) {
}
