package com.smartcoffee.modules.production.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One planned material on a {@link ProductionOrder} (Slice 5A §3, spec
 * "Material snapshot fields"). Immutable after planning: the aggregate root
 * replaces the whole collection exactly once via {@link
 * ProductionOrder#markPlanned} and never edits a row afterwards, so a later
 * Product / RecipeVersion reassignment cannot silently rewrite what the shop
 * floor committed to consume.
 *
 * <p>{@code quantityPlannedBase} is what the planner resolved at PLANNED
 * (yield + wastage + planned output scaling + base-unit conversion + merge).
 * {@code quantityConsumedBase} is written by Phase 5A.2 completion posting —
 * it may scale differently from planned if actual output differs (spec §11).
 */
@Entity
@Table(schema = "production", name = "production_order_materials")
public class ProductionOrderMaterial {
    @Id
    @Column(name = "production_order_material_id")
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "production_order_id", nullable = false)
    private ProductionOrder order;
    @Column(name = "inventory_item_id", nullable = false)
    private UUID inventoryItemId;
    @Column(name = "quantity_planned_base", nullable = false, precision = 18, scale = 4)
    private BigDecimal quantityPlannedBase;
    @Column(name = "quantity_consumed_base", precision = 18, scale = 4)
    private BigDecimal quantityConsumedBase;
    @Column(name = "base_unit_code", nullable = false, length = 20)
    private String baseUnitCode;
    @Column(name = "source_recipe_line_id")
    private UUID sourceRecipeLineId;
    @Column(name = "wastage_allowance_pct", nullable = false, precision = 9, scale = 6)
    private BigDecimal wastageAllowancePct;
    @Column(name = "display_order", nullable = false)
    private int displayOrder;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    protected ProductionOrderMaterial() {
    }

    /** Factory used by {@code ProductionMaterialPlanner} at planning time. */
    public static ProductionOrderMaterial plan(UUID inventoryItemId, BigDecimal quantityPlannedBase,
            String baseUnitCode, UUID sourceRecipeLineId, BigDecimal wastageAllowancePct, int displayOrder) {
        if (inventoryItemId == null) {
            throw new IllegalArgumentException("Material snapshot requires an inventory item");
        }
        if (quantityPlannedBase == null || quantityPlannedBase.signum() <= 0) {
            throw new IllegalArgumentException("Planned material quantity must be greater than zero");
        }
        if (baseUnitCode == null || baseUnitCode.isBlank()) {
            throw new IllegalArgumentException("Material snapshot requires a base unit code");
        }
        var m = new ProductionOrderMaterial();
        m.id = UUID.randomUUID();
        m.inventoryItemId = inventoryItemId;
        m.quantityPlannedBase = quantityPlannedBase;
        m.baseUnitCode = baseUnitCode.trim();
        m.sourceRecipeLineId = sourceRecipeLineId;
        m.wastageAllowancePct = wastageAllowancePct == null ? BigDecimal.ZERO : wastageAllowancePct;
        m.displayOrder = displayOrder;
        m.createdAt = Instant.now();
        return m;
    }

    /** Called by {@link ProductionOrder#markPlanned} to wire the bidirectional association. */
    void attach(ProductionOrder parent) {
        this.order = parent;
    }

    /**
     * Phase 5A.2 hook — completion posting writes the actually-consumed
     * quantity here (may differ from planned when actual output ≠ planned).
     * Enforces the non-negative column CHECK via the domain so a bug in the
     * future posting pipeline cannot persist a negative consumption row.
     */
    public void recordConsumed(BigDecimal consumedBase) {
        if (consumedBase == null || consumedBase.signum() < 0) {
            throw new IllegalArgumentException("Consumed quantity must be non-negative");
        }
        this.quantityConsumedBase = consumedBase;
    }

    public UUID id() {return id;}
    public UUID inventoryItemId() {return inventoryItemId;}
    public BigDecimal quantityPlannedBase() {return quantityPlannedBase;}
    public BigDecimal quantityConsumedBase() {return quantityConsumedBase;}
    public String baseUnitCode() {return baseUnitCode;}
    public UUID sourceRecipeLineId() {return sourceRecipeLineId;}
    public BigDecimal wastageAllowancePct() {return wastageAllowancePct;}
    public int displayOrder() {return displayOrder;}
    public Instant createdAt() {return createdAt;}
}
