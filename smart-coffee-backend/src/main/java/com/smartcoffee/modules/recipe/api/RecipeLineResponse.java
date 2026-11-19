package com.smartcoffee.modules.recipe.api;

import com.smartcoffee.modules.recipe.domain.RecipeScalingRule;
import java.math.BigDecimal;
import java.util.UUID;

/** Read projection of a recipe line, ordered by {@code displayOrder}. */
public record RecipeLineResponse(
        UUID lineId,
        UUID ingredientId,
        BigDecimal quantityRequiredBase,
        BigDecimal wastagePercent,
        UUID unitId,
        RecipeScalingRule scalingRule,
        int displayOrder,
        boolean optional,
        String notes) {
}
