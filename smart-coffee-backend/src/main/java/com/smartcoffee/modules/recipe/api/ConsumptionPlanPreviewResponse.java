package com.smartcoffee.modules.recipe.api;

import com.smartcoffee.modules.recipe.application.RecipeVersionResolver.Source;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Response for the management consumption-plan preview. Reports the resolution that
 * fired (VARIANT / PRODUCT / NONE), the recipe version behind it, and the normalized
 * per-ingredient demand. Empty {@code lines} with {@code source == NONE} is a valid,
 * non-error outcome for legacy / deferred products.
 */
public record ConsumptionPlanPreviewResponse(
        UUID productId,
        UUID variantId,
        UUID recipeVersionId,
        Integer recipeVersionNumber,
        Source resolutionSource,
        BigDecimal requestedProductQuantity,
        Instant resolvedAt,
        List<ConsumptionPlanLineResponse> lines,
        List<String> warnings) {
}
