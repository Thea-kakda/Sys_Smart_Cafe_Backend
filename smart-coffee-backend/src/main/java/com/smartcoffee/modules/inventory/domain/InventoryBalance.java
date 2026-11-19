package com.smartcoffee.modules.inventory.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;

@Entity
@Table(schema = "inventory", name = "inventory_balances")
public class InventoryBalance {
    @EmbeddedId
    private InventoryBalanceId id;
    @Column(name = "current_stock", nullable = false, precision = 18, scale = 4)
    private BigDecimal currentStock;
    @Column(name = "reserved_stock", nullable = false, precision = 18, scale = 4)
    private BigDecimal reservedStock;
    @Column(name = "minimum_stock", nullable = false, precision = 18, scale = 4)
    private BigDecimal minimumStock;
    @Column(name = "average_unit_cost", nullable = false, precision = 14, scale = 4)
    private BigDecimal averageUnitCost;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected InventoryBalance() {
    }

    /**
     * Opens a balance row for a store/ingredient pair that has never been
     * tracked (Full-stack wiring §1.5: first STOCK_IN for an ingredient at a
     * branch). Reserved/minimum start at zero — minimums are a planning
     * concern of the inventory owners, not of the intake path.
     */
    public static InventoryBalance open(InventoryBalanceId id, BigDecimal initialStock, BigDecimal unitCost) {
        var b = new InventoryBalance();
        b.id = id;
        b.currentStock = initialStock;
        b.reservedStock = BigDecimal.ZERO;
        b.minimumStock = BigDecimal.ZERO;
        b.averageUnitCost = unitCost == null ? BigDecimal.ZERO : unitCost;
        b.updatedAt = Instant.now();
        return b;
    }

    public InventoryBalanceId id() {
        return id;
    }

    public BigDecimal currentStock() {
        return currentStock;
    }

    public BigDecimal reservedStock() {
        return reservedStock;
    }

    public BigDecimal availableStock() {
        return currentStock.subtract(reservedStock);
    }

    public BigDecimal minimumStock() {
        return minimumStock;
    }

    public BigDecimal averageUnitCost() {
        return averageUnitCost;
    }

    public long version() {
        return version;
    }
}

