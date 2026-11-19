package com.smartcoffee.modules.production.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Waste/damage line on a {@link ProductionOrder} (Slice 5A §3, spec "Waste
 * fields"). Recorded during DRAFT/PLANNED as an auditable intent, and posted
 * to the inventory ledger in Phase 5A.2 with a DAMAGED stock_transaction
 * referencing this row (Decision doc: "waste records diagnostic quantities
 * and ledger provenance").
 *
 * <p>Raw-material waste must never double-decrement the same quantity, so
 * waste lines that reference a raw ingredient are recorded against the
 * material row's total consumption (spec §10 recommended simple policy), not
 * as a separate decrement. This class exists to hold finished-good waste
 * (a produced unit that failed QC) and other explicitly-attributable losses.
 */
@Entity
@Table(schema = "production", name = "production_order_waste")
public class ProductionOrderWaste {
    @Id
    @Column(name = "production_order_waste_id")
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "production_order_id", nullable = false)
    private ProductionOrder order;
    @Column(name = "inventory_item_id", nullable = false)
    private UUID inventoryItemId;
    @Column(name = "quantity", nullable = false, precision = 18, scale = 4)
    private BigDecimal quantity;
    @Column(name = "unit_code", nullable = false, length = 20)
    private String unitCode;
    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 40)
    private ProductionOrderWasteReason reasonCode;
    @Column(name = "notes", length = 500)
    private String notes;
    @Column(name = "recorded_at")
    private Instant recordedAt;
    @Column(name = "recorded_by")
    private UUID recordedBy;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ProductionOrderWaste() {
    }

    /** Factory used by the command service when a caller records an intended loss. */
    public static ProductionOrderWaste create(UUID inventoryItemId, BigDecimal quantity, String unitCode,
            ProductionOrderWasteReason reasonCode, String notes, UUID actorId) {
        if (inventoryItemId == null) {
            throw new IllegalArgumentException("Waste requires an inventory item");
        }
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("Waste quantity must be greater than zero");
        }
        if (unitCode == null || unitCode.isBlank()) {
            throw new IllegalArgumentException("Waste requires a unit code");
        }
        if (reasonCode == null) {
            throw new IllegalArgumentException("Waste requires a reason code");
        }
        var w = new ProductionOrderWaste();
        w.id = UUID.randomUUID();
        w.inventoryItemId = inventoryItemId;
        w.quantity = quantity;
        w.unitCode = unitCode.trim();
        w.reasonCode = reasonCode;
        w.notes = (notes == null || notes.isBlank()) ? null : notes.trim();
        w.recordedAt = Instant.now();
        w.recordedBy = actorId;
        w.createdAt = w.recordedAt;
        return w;
    }

    void attach(ProductionOrder parent) {
        this.order = parent;
    }

    public UUID id() {return id;}
    public UUID inventoryItemId() {return inventoryItemId;}
    public BigDecimal quantity() {return quantity;}
    public String unitCode() {return unitCode;}
    public ProductionOrderWasteReason reasonCode() {return reasonCode;}
    public String notes() {return notes;}
    public Instant recordedAt() {return recordedAt;}
    public UUID recordedBy() {return recordedBy;}
    public Instant createdAt() {return createdAt;}
}
