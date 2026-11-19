package com.smartcoffee.modules.inventory.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One ingredient line on an inter-branch stock transfer (V035). Quantity is in
 * the ingredient's base unit (matching the balance ledger) and must be
 * positive; unit cost is the shipped cost carried to the destination's moving
 * average on receive. The line itself never mutates a balance — the transfer
 * service writes the paired ledger through the guarded native mutations.
 */
@Entity
@Table(schema = "inventory", name = "stock_transfer_lines")
public class StockTransferLine {
    @Id
    @Column(name = "stock_transfer_line_id")
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transfer_id", nullable = false)
    private StockTransfer transfer;
    @Column(name = "ingredient_id", nullable = false)
    private UUID ingredientId;
    @Column(name = "quantity", nullable = false, precision = 18, scale = 4)
    private BigDecimal quantity;
    @Column(name = "unit_cost", nullable = false, precision = 14, scale = 4)
    private BigDecimal unitCost;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected StockTransferLine() {
    }

    public static StockTransferLine create(UUID ingredientId, BigDecimal quantity, BigDecimal unitCost) {
        if (ingredientId == null) {
            throw new IllegalArgumentException("Ingredient is required");
        }
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("Transfer quantity must be positive");
        }
        if (unitCost == null || unitCost.signum() < 0) {
            throw new IllegalArgumentException("Unit cost must be zero or positive");
        }
        var line = new StockTransferLine();
        line.id = UUID.randomUUID();
        line.ingredientId = ingredientId;
        line.quantity = quantity;
        line.unitCost = unitCost;
        line.createdAt = Instant.now();
        return line;
    }

    void attach(StockTransfer transfer) {
        this.transfer = transfer;
    }

    public UUID id() {
        return id;
    }

    public UUID ingredientId() {
        return ingredientId;
    }

    public BigDecimal quantity() {
        return quantity;
    }

    public BigDecimal unitCost() {
        return unitCost;
    }
}
