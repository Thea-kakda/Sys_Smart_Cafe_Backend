package com.smartcoffee.modules.recipe.api.request;

import com.smartcoffee.modules.recipe.domain.RecipeScalingRule;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * One recipe line in a draft create/replace payload. {@code scalingRule} and
 * {@code unitId} are optional (defaults FIXED / null); they are metadata for
 * Slice 2B planning and do not alter checkout demand in 2A. Field ranges mirror
 * the DB checks so shape errors surface as stable 400s before persistence.
 */
public record RecipeItemRequest(
        @NotNull UUID ingredientId,
        @NotNull @Positive BigDecimal quantityRequiredBase,
        @PositiveOrZero BigDecimal wastagePercent,
        UUID unitId,
        RecipeScalingRule scalingRule,
        @PositiveOrZero int displayOrder,
        boolean optional,
        @Size(max = 2000) String notes) {
}
