package com.smartcoffee.modules.inventory.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Management-shaped unit of measure: the POS-facing {@link UnitOfMeasureResponse}
 * fields plus {@code active}, timestamps and the optimistic-lock {@code version}
 * so the Dashboard can chain PATCH calls (M2).
 */
public record UnitManagementResponse(UUID unitId, String code, String name, String type, int decimalScale,
        BigDecimal conversionFactorToBase, boolean baseUnit, boolean active, Instant createdAt, Instant updatedAt,
        long version) {
}
