package com.smartcoffee.modules.inventory.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * One ordered ingredient on a purchase order (V033). {@code quantityReceived}
 * is cumulative and can never exceed {@code quantityOrdered} — the guard lives
 * in {@link #markReceived(BigDecimal)} and is backed by the
 * {@code ck_po_line_received} CHECK at the database. Received quantity is
 * driven only by goods-receipt lines (never a setter), so the aggregate stays
 * consistent with the ledger.
 */
@Entity
@Table(schema = "inventory", name = "purchase_order_lines")
public class PurchaseOrderLine {
    @Id
    @Column(name = "purchase_order_line_id")
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id", nullable = false)
    private PurchaseOrder order;
    @Column(name = "ingredient_id", nullable = false)
    private UUID ingredientId;
    @Column(name = "quantity_ordered", nullable = false, precision = 18, scale = 4)
    private BigDecimal quantityOrdered;
    @Column(name = "quantity_received", nullable = false, precision = 18, scale = 4)
    private BigDecimal quantityReceived;
    @Column(name = "unit_cost", nullable = false, precision = 14, scale = 4)
    private BigDecimal unitCost;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected PurchaseOrderLine() {
    }

    public static PurchaseOrderLine create(UUID ingredientId, BigDecimal quantityOrdered, BigDecimal unitCost) {
        if (ingredientId == null) {
            throw new IllegalArgumentException("Ingredient is required");
        }
        if (quantityOrdered == null || quantityOrdered.signum() <= 0) {
            throw new IllegalArgumentException("Ordered quantity must be positive");
        }
        if (unitCost == null || unitCost.signum() < 0) {
            throw new IllegalArgumentException("Unit cost must be zero or positive");
        }
        var line = new PurchaseOrderLine();
        line.id = UUID.randomUUID();
        line.ingredientId = ingredientId;
        line.quantityOrdered = quantityOrdered;
        line.quantityReceived = BigDecimal.ZERO;
        line.unitCost = unitCost;
        var now = Instant.now();
        line.createdAt = now;
        line.updatedAt = now;
        return line;
    }

    void attach(PurchaseOrder order) {
        this.order = order;
    }

    /**
     * Adds {@code quantity} to the received total, refusing over-receipt. The
     * caller (goods-receipt service) has already applied the physical stock-in;
     * a rejection here rolls the whole transaction back so the ledger and the
     * PO line can never disagree.
     */
    public void markReceived(BigDecimal quantity) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("Received quantity must be positive");
        }
        BigDecimal next = quantityReceived.add(quantity);
        if (next.compareTo(quantityOrdered) > 0) {
            throw new IllegalStateException("OVER_RECEIPT");
        }
        this.quantityReceived = next;
        this.updatedAt = Instant.now();
    }

    public boolean fullyReceived() {
        return quantityReceived.compareTo(quantityOrdered) >= 0;
    }

    public UUID id() {
        return id;
    }

    public UUID ingredientId() {
        return ingredientId;
    }

    public BigDecimal quantityOrdered() {
        return quantityOrdered;
    }

    public BigDecimal quantityReceived() {
        return quantityReceived;
    }

    public BigDecimal outstanding() {
        return quantityOrdered.subtract(quantityReceived);
    }

    public BigDecimal unitCost() {
        return unitCost;
    }
}
