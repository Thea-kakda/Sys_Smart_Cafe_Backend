package com.smartcoffee.modules.inventory.api;

import java.util.UUID;

/**
 * Body of {@code POST /api/v1/management/inventory/reconciliation/run} (Slice 2E,
 * spec §13). {@code storeId} may be null only for a global administrator; a
 * store-scoped operator must pass one of their own stores. {@code dryRun} defaults
 * to true when omitted — an operator has to opt in explicitly to move stock.
 * {@code maxItems} is a per-detector cap, clamped server-side.
 */
public record ReconciliationRunRequest(UUID storeId, Boolean dryRun, Integer maxItems) {
}
