package com.smartcoffee.modules.production.application;

import com.smartcoffee.modules.production.api.ProductionOrderResponse;
import com.smartcoffee.modules.production.api.ProductionOrderResponse.MaterialLine;
import com.smartcoffee.modules.production.api.ProductionOrderResponse.OutputLine;
import com.smartcoffee.modules.production.api.ProductionOrderResponse.WasteLine;
import com.smartcoffee.modules.production.domain.ProductionOrder;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Projects the {@link ProductionOrder} aggregate onto the public management
 * DTO (Slice 5A §22). Kept separate from the command/query services so the
 * wire shape has exactly one home; anything that changes the DTO updates this
 * mapper only.
 */
@Component
public class ProductionOrderMapper {

    public ProductionOrderResponse toResponse(ProductionOrder o) {
        List<MaterialLine> materials = o.materials().stream()
                .map(m -> new MaterialLine(
                        m.id(),
                        m.inventoryItemId(),
                        m.quantityPlannedBase(),
                        m.quantityConsumedBase(),
                        m.baseUnitCode(),
                        m.wastageAllowancePct(),
                        m.displayOrder()))
                .toList();
        List<OutputLine> outputs = o.outputs().stream()
                .map(x -> new OutputLine(
                        x.id(),
                        x.outputInventoryItemId(),
                        x.plannedQuantity(),
                        x.actualQuantity(),
                        x.unitCode(),
                        x.batchId(),
                        x.expiryDate(),
                        x.qualityStatus()))
                .toList();
        List<WasteLine> waste = o.waste().stream()
                .map(w -> new WasteLine(
                        w.id(),
                        w.inventoryItemId(),
                        w.quantity(),
                        w.unitCode(),
                        w.reasonCode().name(),
                        w.notes(),
                        w.recordedAt(),
                        w.recordedBy()))
                .toList();
        return new ProductionOrderResponse(
                o.id(), o.number(), o.storeId(), o.productId(), o.variantId(),
                o.recipeVersionId(), o.recipeVersionNumber(), o.status().name(),
                o.plannedOutputQuantity(), o.plannedOutputUnitId(), o.actualOutputQuantity(),
                o.scheduledFor(), o.startedAt(), o.startedBy(),
                o.completedAt(), o.completedBy(), o.cancelledAt(), o.cancelledBy(),
                o.cancellationReason(), o.closedAt(), o.closedBy(), o.closeNote(),
                o.notes(), o.createdAt(), o.createdBy(), o.updatedAt(), o.updatedBy(),
                o.version(), materials, outputs, waste);
    }
}
