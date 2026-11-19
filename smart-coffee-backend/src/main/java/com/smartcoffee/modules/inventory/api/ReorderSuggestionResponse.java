package com.smartcoffee.modules.inventory.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A reorder suggestion for a low-stock ingredient (M2): the low-stock read plus
 * the quantity needed to bring the balance back up to its ceiling
 * ({@code maxStock - currentStock}, clamped at zero). A suggestion is advisory;
 * turning one into a purchase order is an explicit {@code inventory:reorder}
 * action.
 */
public record ReorderSuggestionResponse(UUID ingredientId, String ingredientCode, String ingredientName,
        String baseUnitCode, BigDecimal availableStock, BigDecimal reorderPoint, BigDecimal maxStock,
        BigDecimal suggestedQuantity) {
}
