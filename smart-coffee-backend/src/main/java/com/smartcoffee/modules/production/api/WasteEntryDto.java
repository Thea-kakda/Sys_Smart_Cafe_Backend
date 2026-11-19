package com.smartcoffee.modules.production.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One waste entry inside a {@link CompleteProductionOrderRequest} (Slice 5A.2
 * §4). The waste inventory item must reference either a planned material on
 * the order or the resolved output item — a caller-supplied unrelated item is
 * rejected at validation time.
 */
public record WasteEntryDto(
        UUID inventoryItemId,
        BigDecimal quantity,
        String unitCode,
        String reasonCode,
        String notes) {
}
