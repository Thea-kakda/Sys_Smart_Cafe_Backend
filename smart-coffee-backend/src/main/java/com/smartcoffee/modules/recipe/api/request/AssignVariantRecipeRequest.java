package com.smartcoffee.modules.recipe.api.request;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Sets (or clears) the explicit recipe-version override on a product variant.
 * A non-null {@code recipeVersionId} must reference an ACTIVE version belonging
 * to the variant's own product; null clears the override so the variant falls
 * back to the product's ACTIVE recipe. {@code expectedVariantVersion} echoes the
 * variant's optimistic lock (409 VARIANT_VERSION_CONFLICT on stale submit).
 */
public record AssignVariantRecipeRequest(
        UUID recipeVersionId,
        @NotNull Long expectedVariantVersion) {
}
