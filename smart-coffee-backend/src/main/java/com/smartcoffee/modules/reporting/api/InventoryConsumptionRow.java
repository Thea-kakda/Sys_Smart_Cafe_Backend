package com.smartcoffee.modules.reporting.api;

import java.math.BigDecimal;

/**
 * Reconstructed ingredient consumption for one product/ingredient pair inside
 * the report window, from the recipe version each order item actually used.
 * Quantity-only by design — see InventoryConsumptionReport for why cost and
 * gross margin are a documented BACKEND API GAP.
 */
public record InventoryConsumptionRow(
        String productName,
        String ingredientName,
        String unitCode,
        BigDecimal unitsConsumed) {
}
