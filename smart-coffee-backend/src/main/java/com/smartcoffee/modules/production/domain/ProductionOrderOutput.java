package com.smartcoffee.modules.production.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One finished-good output row on a {@link ProductionOrder} (Slice 5A §3,
 * spec "Output fields"). Phase 5A.1 writes exactly one row at planning time
 * recording the resolved output inventory item + planned quantity; Phase
 * 5A.2 completion posting sets {@code actualQuantity} and (when the output
 * ingredient has {@code track_batches=true}) links the created {@code
 * inventory.inventory_batches} row.
 *
 * <p>The {@code outputInventoryItemId} is resolved by Decision 1's Variant →
 * Product → error cascade; nothing in this row implies the item was inferred
 * (spec §6, rule 5: "Do not infer output item from Product ID").
 */
@Entity
@Table(schema = "production", name = "production_order_outputs")
public class ProductionOrderOutput {
    @Id
    @Column(name = "production_order_output_id")
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "production_order_id", nullable = false)
    private ProductionOrder order;
    @Column(name = "output_inventory_item_id", nullable = false)
    private UUID outputInventoryItemId;
    @Column(name = "planned_quantity", nullable = false, precision = 18, scale = 4)
    private BigDecimal plannedQuantity;
    @Column(name = "actual_quantity", precision = 18, scale = 4)
    private BigDecimal actualQuantity;
    @Column(name = "unit_code", nullable = false, length = 20)
    private String unitCode;
    @Column(name = "batch_id")
    private UUID batchId;
    @Column(name = "expiry_date")
    private LocalDate expiryDate;
    @Column(name = "quality_status", length = 30)
    private String qualityStatus;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ProductionOrderOutput() {
    }

    /** Factory used by the planner when it freezes the aggregate at PLANNED. */
    public static ProductionOrderOutput plan(UUID outputInventoryItemId, BigDecimal plannedQuantity,
            String unitCode) {
        if (outputInventoryItemId == null) {
            throw new IllegalArgumentException("Output row requires an inventory item");
        }
        if (plannedQuantity == null || plannedQuantity.signum() <= 0) {
            throw new IllegalArgumentException("Planned output quantity must be greater than zero");
        }
        if (unitCode == null || unitCode.isBlank()) {
            throw new IllegalArgumentException("Output row requires a unit code");
        }
        var o = new ProductionOrderOutput();
        o.id = UUID.randomUUID();
        o.outputInventoryItemId = outputInventoryItemId;
        o.plannedQuantity = plannedQuantity;
        o.unitCode = unitCode.trim();
        o.createdAt = Instant.now();
        return o;
    }

    void attach(ProductionOrder parent) {
        this.order = parent;
    }

    /** Phase 5A.2 hook: completion posting records the actual good output + batch metadata. */
    public void recordActual(BigDecimal actualQuantity, UUID batchId, LocalDate expiryDate, String qualityStatus) {
        if (actualQuantity == null || actualQuantity.signum() < 0) {
            throw new IllegalArgumentException("Actual output quantity must be non-negative");
        }
        this.actualQuantity = actualQuantity;
        this.batchId = batchId;
        this.expiryDate = expiryDate;
        this.qualityStatus = qualityStatus;
    }

    public UUID id() {return id;}
    public UUID outputInventoryItemId() {return outputInventoryItemId;}
    public BigDecimal plannedQuantity() {return plannedQuantity;}
    public BigDecimal actualQuantity() {return actualQuantity;}
    public String unitCode() {return unitCode;}
    public UUID batchId() {return batchId;}
    public LocalDate expiryDate() {return expiryDate;}
    public String qualityStatus() {return qualityStatus;}
    public Instant createdAt() {return createdAt;}
}
