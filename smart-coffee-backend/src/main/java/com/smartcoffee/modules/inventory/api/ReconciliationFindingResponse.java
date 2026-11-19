package com.smartcoffee.modules.inventory.api;

import com.smartcoffee.modules.inventory.domain.reconciliation.ReconciliationFinding;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Safe operational view of one reconciliation finding (§13). A report row only —
 * it carries no customer or supplier data and never claims to be authoritative
 * stock. Quantities are the diagnostic expected/actual pair captured at scan time.
 */
public record ReconciliationFindingResponse(UUID findingId, UUID runId, UUID storeId, UUID inventoryItemId,
        UUID reservationId, String anomalyType, String severity, BigDecimal expectedQuantity,
        BigDecimal actualQuantity, boolean repairable, String status, UUID orderId, UUID cartId, Instant createdAt,
        Instant repairedAt, UUID repairedBy, String repairReason) {

    public static ReconciliationFindingResponse from(ReconciliationFinding f) {
        return new ReconciliationFindingResponse(f.id(), f.runId(), f.storeId(), f.inventoryItemId(),
                f.reservationId(), f.anomalyType().name(), f.severity().name(), f.expectedQuantity(),
                f.actualQuantity(), f.repairable(), f.status().name(), f.openedOrderId(), f.openedCartId(),
                f.createdAt(), f.repairedAt(), f.repairedBy(), f.repairReason());
    }
}
