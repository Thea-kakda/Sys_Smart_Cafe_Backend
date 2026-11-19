package com.smartcoffee.modules.recipe.application;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * One normalized ingredient requirement of a {@link CalculatedConsumptionPlan},
 * expressed in the ingredient's base inventory unit and already scaled by the
 * requested product quantity. Immutable; carries only non-sensitive identifiers
 * (never cost, supplier, or balances).
 *
 * @param ingredientId     the inventory item this demand targets
 * @param ingredientCode   human-readable ingredient code
 * @param ingredientName   human-readable ingredient name
 * @param quantity         final decimal-safe demand in the base unit (&gt; 0)
 * @param baseUnitCode     unit {@code quantity} is expressed in
 * @param source           provenance of the line
 * @param appliedOptionIds customization option ids that shaped this line
 * @param displayOrder     deterministic ordering key (recipe line order first)
 * @param optional         true only when the line came solely from optional base lines
 */
public record CalculatedConsumptionLine(
        UUID ingredientId,
        String ingredientCode,
        String ingredientName,
        BigDecimal quantity,
        String baseUnitCode,
        ConsumptionLineSource source,
        List<UUID> appliedOptionIds,
        int displayOrder,
        boolean optional) {

    public CalculatedConsumptionLine {
        appliedOptionIds = appliedOptionIds == null ? List.of() : List.copyOf(appliedOptionIds);
    }
}
