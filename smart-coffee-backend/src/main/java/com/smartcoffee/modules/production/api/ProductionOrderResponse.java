package com.smartcoffee.modules.production.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Full read projection of a {@code ProductionOrder} aggregate (Slice 5A §23).
 * Every response carries the server-current {@code version} so the next
 * mutation echoes the correct {@code expectedVersion} without assuming
 * {@code version + 1} (spec §23 explicitly forbids that).
 */
public record ProductionOrderResponse(
        UUID productionOrderId,
        String productionOrderNumber,
        UUID storeId,
        UUID productId,
        UUID variantId,
        UUID recipeVersionId,
        Integer recipeVersionNumber,
        String status,
        BigDecimal plannedOutputQuantity,
        UUID plannedOutputUnitId,
        BigDecimal actualOutputQuantity,
        Instant scheduledFor,
        Instant startedAt,
        UUID startedBy,
        Instant completedAt,
        UUID completedBy,
        Instant cancelledAt,
        UUID cancelledBy,
        String cancellationReason,
        Instant closedAt,
        UUID closedBy,
        String closeNote,
        String notes,
        Instant createdAt,
        UUID createdBy,
        Instant updatedAt,
        UUID updatedBy,
        long version,
        List<MaterialLine> materials,
        List<OutputLine> outputs,
        List<WasteLine> waste) {

    public record MaterialLine(UUID productionOrderMaterialId,
                               UUID inventoryItemId,
                               BigDecimal quantityPlannedBase,
                               BigDecimal quantityConsumedBase,
                               String baseUnitCode,
                               BigDecimal wastageAllowancePct,
                               int displayOrder) {}

    public record OutputLine(UUID productionOrderOutputId,
                             UUID outputInventoryItemId,
                             BigDecimal plannedQuantity,
                             BigDecimal actualQuantity,
                             String unitCode,
                             UUID batchId,
                             LocalDate expiryDate,
                             String qualityStatus) {}

    public record WasteLine(UUID productionOrderWasteId,
                            UUID inventoryItemId,
                            BigDecimal quantity,
                            String unitCode,
                            String reasonCode,
                            String notes,
                            Instant recordedAt,
                            UUID recordedBy) {}
}
