package com.smartcoffee.modules.recipe.api;

import com.smartcoffee.modules.recipe.domain.RecipeStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Read projection of a {@code RecipeVersion} and its ordered lines. Exposes the
 * optimistic-lock {@code version} so editors can echo it back on update /
 * activation. Lines are ordered deterministically by displayOrder.
 */
public record RecipeVersionResponse(
        UUID recipeVersionId,
        UUID productId,
        int versionNumber,
        RecipeStatus status,
        Instant effectiveFrom,
        Instant effectiveTo,
        BigDecimal yieldQuantity,
        UUID yieldUnitId,
        String notes,
        Instant activatedAt,
        UUID activatedBy,
        Instant createdAt,
        Instant updatedAt,
        long version,
        List<RecipeLineResponse> lines) {
}
