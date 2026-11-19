package com.smartcoffee.modules.inventory.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One ledger line of the stock movement feed (Full-stack wiring §1.5): the
 * transaction header fields plus this line's ingredient effect. Rows are
 * newest-first; SALE_CONSUMPTION rows carry a null reason (system-generated)
 * and an ORDER reference, manual writes carry the operator reason.
 */
public record StockMovementResponse(UUID transactionId, String transactionNumber, String transactionType,
        String referenceType, UUID referenceId, UUID actorId, String reason, Instant occurredAt,
        UUID ingredientId, String ingredientCode, String ingredientName, String unitCode, BigDecimal delta,
        BigDecimal unitCost, BigDecimal balanceBefore, BigDecimal balanceAfter) {
}
