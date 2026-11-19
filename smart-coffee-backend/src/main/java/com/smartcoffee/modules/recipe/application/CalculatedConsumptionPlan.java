package com.smartcoffee.modules.recipe.application;

import com.smartcoffee.modules.recipe.application.RecipeVersionResolver.Source;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Authoritative, deterministic, read-only ingredient demand plan for ONE
 * configured order item (product / variant / quantity / customizations). Slice 2B
 * only <em>calculates</em> — nothing here is persisted, and no recipe / option /
 * stock state is mutated.
 *
 * <p>{@code resolutionSource} reuses the shared {@link Source} vocabulary so the
 * plan reports the exact rule that produced the recipe (variant override, product
 * fallback, or none). A {@code NONE} source yields an empty {@code lines} list —
 * a legacy product without a recipe is not an error (see the service's per
 * {@code StockMode} contract), and RECIPE_BASED products without an ACTIVE recipe
 * are rejected before a plan is ever built.
 *
 * @param productId                the product being planned
 * @param variantId                the sellable variant, when supplied
 * @param recipeVersionId          the resolved ACTIVE version (null when NONE)
 * @param recipeVersionNumber      its version number (null when NONE)
 * @param resolutionSource         VARIANT / PRODUCT / NONE
 * @param requestedProductQuantity the sellable quantity the plan covers
 * @param resolvedAt               calculation instant
 * @param lines                    normalized, product-quantity-scaled demand lines
 * @param warnings                 non-fatal advisories (e.g. deferred modes)
 */
public record CalculatedConsumptionPlan(
        UUID productId,
        UUID variantId,
        UUID recipeVersionId,
        Integer recipeVersionNumber,
        Source resolutionSource,
        BigDecimal requestedProductQuantity,
        Instant resolvedAt,
        List<CalculatedConsumptionLine> lines,
        List<String> warnings) {

    public CalculatedConsumptionPlan {
        lines = lines == null ? List.of() : List.copyOf(lines);
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }
}
