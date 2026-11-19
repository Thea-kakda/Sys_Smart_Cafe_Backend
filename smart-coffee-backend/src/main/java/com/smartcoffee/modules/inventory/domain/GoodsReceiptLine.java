package com.smartcoffee.modules.inventory.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One received line on a goods receipt (V033). Carries the physical
 * quantity/cost plus the optional lot/expiry that M3 batch tracking later keys
 * off. The line itself never mutates a balance — the goods-receipt service
 * writes the ledger and calls {@link PurchaseOrderLine#markReceived}.
 */
@Entity
@Table(schema = "inventory", name = "goods_receipt_lines")
public class GoodsReceiptLine {
    @Id
    @Column(name = "goods_receipt_line_id")
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "goods_receipt_id", nullable = false)
    private GoodsReceipt receipt;
    @Column(name = "ingredient_id", nullable = false)
    private UUID ingredientId;
    @Column(name = "quantity", nullable = false, precision = 18, scale = 4)
    private BigDecimal quantity;
    @Column(name = "unit_cost", nullable = false, precision = 14, scale = 4)
    private BigDecimal unitCost;
    @Column(name = "lot_number", length = 60)
    private String lotNumber;
    @Column(name = "expiry_date")
    private LocalDate expiryDate;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected GoodsReceiptLine() {
    }

    public static GoodsReceiptLine create(UUID ingredientId, BigDecimal quantity, BigDecimal unitCost,
            String lotNumber, LocalDate expiryDate) {
        if (ingredientId == null) {
            throw new IllegalArgumentException("Ingredient is required");
        }
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("Received quantity must be positive");
        }
        if (unitCost == null || unitCost.signum() < 0) {
            throw new IllegalArgumentException("Unit cost must be zero or positive");
        }
        var line = new GoodsReceiptLine();
        line.id = UUID.randomUUID();
        line.ingredientId = ingredientId;
        line.quantity = quantity;
        line.unitCost = unitCost;
        line.lotNumber = lotNumber == null || lotNumber.isBlank() ? null : lotNumber.trim();
        line.expiryDate = expiryDate;
        line.createdAt = Instant.now();
        return line;
    }

    void attach(GoodsReceipt receipt) {
        this.receipt = receipt;
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

    public String lotNumber() {
        return lotNumber;
    }

    public LocalDate expiryDate() {
        return expiryDate;
    }
}
