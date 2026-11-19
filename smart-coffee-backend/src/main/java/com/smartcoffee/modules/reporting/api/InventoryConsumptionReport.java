package com.smartcoffee.modules.reporting.api;

import java.time.Instant;
import java.util.List;

/**
 * Inventory consumption envelope. grossMarginAvailable is false and
 * cogsAvailable is false because historical COGS cannot be reconstructed from
 * persisted data: ingredient costs exist only as current values
 * (inventory.ingredients.standard_cost / inventory_balances.average_unit_cost),
 * ledger unit_cost is nullable and currency-less, and no immutable cost
 * snapshot is taken per sale. Reporting margin from current costs against
 * historical sales would be an unverified approximation, so the API returns
 * real quantities only and labels the gap explicitly.
 */
public record InventoryConsumptionReport(
        Instant from,
        Instant to,
        List<InventoryConsumptionRow> rows,
        long itemsWithoutRecipeSnapshot,
        boolean truncated,
        boolean cogsAvailable,
        boolean grossMarginAvailable,
        String unavailableReason) {
}
