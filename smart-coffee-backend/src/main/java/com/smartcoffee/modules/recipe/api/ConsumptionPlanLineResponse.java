package com.smartcoffee.modules.recipe.api;

import com.smartcoffee.modules.recipe.application.ConsumptionLineSource;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Read projection of one calculated consumption line. Deliberately omits supplier,
 * cost and inventory-balance data — the preview exposes only what is needed to
 * verify the demand plan (what and how much, plus provenance).
 */
public record ConsumptionPlanLineResponse(
        UUID ingredientId,
        String ingredientCode,
        String ingredientName,
        BigDecimal quantity,
        String baseUnitCode,
        ConsumptionLineSource source,
        List<UUID> appliedOptionIds,
        int displayOrder,
        boolean optional) {
}
