package com.smartcoffee.modules.inventory.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Result of an applied stock adjustment: the ledger transaction identity,
 * the line effect (before → after), and the balance row's new version so
 * clients can chain optimistic follow-ups.
 */
public record InventoryAdjustmentResponse(UUID transactionId, String transactionNumber, UUID storeId,
        UUID ingredientId, String type, String direction, BigDecimal quantity, BigDecimal balanceBefore,
        BigDecimal balanceAfter, BigDecimal averageUnitCost, String reason, long balanceVersion) {
}
