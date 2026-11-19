package com.smartcoffee.modules.inventory.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One tracked lot of an ingredient at one store (V038). Batch tracking is
 * opt-in per ingredient ({@code ingredients.track_batches}); for a tracked
 * ingredient the sum of its ACTIVE batches is kept synchronized with the
 * aggregate {@link InventoryBalance#currentStock()}. The aggregate stays the
 * authority for availability — batches add expiry/lot granularity on top and
 * are drawn FEFO (earliest unexpired first) by {@link
 * com.smartcoffee.modules.inventory.application.FefoConsumptionService}.
 *
 * <p>Physical quantity changes are applied only through the guarded native
 * statements on {@link com.smartcoffee.modules.inventory.persistence.InventoryBatchRepository}
 * (never a setter) so a lost race can never drive a batch negative. Lot numbers
 * are never null in storage: an un-lotted receipt is recorded under the
 * {@link #UNLOTTED} sentinel so the {@code (store, ingredient, lot)} uniqueness
 * holds and repeat receipts merge into one batch. Optimistic locking via
 * {@code @Version}; a batch is retired, never deleted (A3).
 */
@Entity
@Table(schema = "inventory", name = "inventory_batches")
public class InventoryBatch {

    /** Sentinel lot for tracked receipts that arrive without a lot number. */
    public static final String UNLOTTED = "UNLOTTED";

    @Id
    @Column(name = "batch_id")
    private UUID id;
    @Column(name = "store_id", nullable = false)
    private UUID storeId;
    @Column(name = "ingredient_id", nullable = false)
    private UUID ingredientId;
    @Column(name = "lot_number", nullable = false, length = 60)
    private String lotNumber;
    @Column(name = "expiry_date")
    private LocalDate expiryDate;
    @Column(name = "quantity_base", nullable = false, precision = 18, scale = 4)
    private BigDecimal quantityBase;
    @Column(name = "unit_cost", nullable = false, precision = 14, scale = 4)
    private BigDecimal unitCost;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InventoryBatchStatus status;
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected InventoryBatch() {
    }

    /** Opens a fresh ACTIVE batch for a receipt line that has no existing lot row. */
    public static InventoryBatch open(UUID storeId, UUID ingredientId, String lotNumber, LocalDate expiryDate,
            BigDecimal quantity, BigDecimal unitCost) {
        if (storeId == null || ingredientId == null) {
            throw new IllegalArgumentException("Store and ingredient are required");
        }
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("A new batch must open with a positive quantity");
        }
        var b = new InventoryBatch();
        b.id = UUID.randomUUID();
        b.storeId = storeId;
        b.ingredientId = ingredientId;
        b.lotNumber = normalizeLot(lotNumber);
        b.expiryDate = expiryDate;
        b.quantityBase = quantity;
        b.unitCost = unitCost == null ? BigDecimal.ZERO : unitCost;
        b.status = InventoryBatchStatus.ACTIVE;
        var now = Instant.now();
        b.receivedAt = now;
        b.createdAt = now;
        b.updatedAt = now;
        return b;
    }

    private static String normalizeLot(String lot) {
        return lot == null || lot.isBlank() ? UNLOTTED : lot.trim();
    }

    /** Effective lot key used for uniqueness/merge (never null). */
    public static String effectiveLot(String lot) {
        return normalizeLot(lot);
    }

    public UUID id() {
        return id;
    }

    public UUID storeId() {
        return storeId;
    }

    public UUID ingredientId() {
        return ingredientId;
    }

    public String lotNumber() {
        return lotNumber;
    }

    public LocalDate expiryDate() {
        return expiryDate;
    }

    public BigDecimal quantityBase() {
        return quantityBase;
    }

    public BigDecimal unitCost() {
        return unitCost;
    }

    public InventoryBatchStatus status() {
        return status;
    }

    public Instant receivedAt() {
        return receivedAt;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    public long version() {
        return version;
    }
}
