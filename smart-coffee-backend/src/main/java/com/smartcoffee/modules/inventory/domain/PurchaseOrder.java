package com.smartcoffee.modules.inventory.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Purchase order header (V033). Owns its lines (cascade) and drives the status
 * machine: a DRAFT can be edited/submitted/cancelled; once SUBMITTED it can be
 * cancelled (before any receipt) and moved toward RECEIVED through
 * PARTIALLY_RECEIVED as goods receipts land; a fully-received order can be
 * CLOSED. Cancel/close are the only "deletions" (A3: no hard deletes).
 * Optimistic locking via {@code @Version}; the PO number is generated here.
 */
@SuppressWarnings("null")
@Entity
@Table(schema = "inventory", name = "purchase_orders")
public class PurchaseOrder {
    @Id
    @Column(name = "purchase_order_id")
    private UUID id;
    @Column(name = "po_number", nullable = false, unique = true)
    private String number;
    @Column(name = "store_id", nullable = false)
    private UUID storeId;
    @Column(name = "supplier_id", nullable = false)
    private UUID supplierId;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PurchaseOrderStatus status;
    @Column(name = "ordered_at", nullable = false)
    private Instant orderedAt;
    @Column(name = "expected_at")
    private Instant expectedAt;
    @Column(length = 1000)
    private String notes;
    @Column(name = "created_by_user_id")
    private UUID createdByUserId;
    @OneToMany(mappedBy = "order", cascade = CascadeType.ALL, orphanRemoval = false)
    private List<PurchaseOrderLine> lines = new ArrayList<>();
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected PurchaseOrder() {
    }

    public static PurchaseOrder draft(UUID storeId, UUID supplierId, Instant expectedAt, String notes,
            UUID createdBy, List<PurchaseOrderLine> lines) {
        if (storeId == null) {
            throw new IllegalArgumentException("Store is required");
        }
        if (supplierId == null) {
            throw new IllegalArgumentException("Supplier is required");
        }
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("A purchase order needs at least one line");
        }
        var po = new PurchaseOrder();
        po.id = UUID.randomUUID();
        po.number = "PO-" + po.id;
        po.storeId = storeId;
        po.supplierId = supplierId;
        po.status = PurchaseOrderStatus.DRAFT;
        var now = Instant.now();
        po.orderedAt = now;
        po.expectedAt = expectedAt;
        po.notes = notes == null || notes.isBlank() ? null : notes.trim();
        po.createdByUserId = createdBy;
        po.lines = new ArrayList<>();
        lines.forEach(l -> {
            l.attach(po);
            po.lines.add(l);
        });
        po.createdAt = now;
        po.updatedAt = now;
        return po;
    }

    /** DRAFT -> SUBMITTED. Requires at least one line (guaranteed at creation). */
    public void submit() {
        if (status != PurchaseOrderStatus.DRAFT) {
            throw new IllegalStateException("Only a DRAFT purchase order can be submitted");
        }
        this.status = PurchaseOrderStatus.SUBMITTED;
        touch();
    }

    /** DRAFT or SUBMITTED (pre-receipt) -> CANCELLED. */
    public void cancel() {
        if (status != PurchaseOrderStatus.DRAFT && status != PurchaseOrderStatus.SUBMITTED) {
            throw new IllegalStateException("Only an unreceived purchase order can be cancelled");
        }
        this.status = PurchaseOrderStatus.CANCELLED;
        touch();
    }

    /**
     * Goods receipts call this after their lines are applied: recompute the
     * header status from the line totals. A SUBMITTED/PARTIALLY_RECEIVED order
     * becomes RECEIVED once every line is fully received; otherwise it is
     * PARTIALLY_RECEIVED. Illegal in any non-receivable state.
     */
    public void applyReceiptTransition() {
        if (status != PurchaseOrderStatus.SUBMITTED && status != PurchaseOrderStatus.PARTIALLY_RECEIVED) {
            throw new IllegalStateException("Purchase order is not receivable in status " + status);
        }
        this.status = lines.stream().allMatch(PurchaseOrderLine::fullyReceived)
                ? PurchaseOrderStatus.RECEIVED
                : PurchaseOrderStatus.PARTIALLY_RECEIVED;
        touch();
    }

    /** RECEIVED -> CLOSED (finished; no further receipt expected). */
    public void close() {
        if (status != PurchaseOrderStatus.RECEIVED) {
            throw new IllegalStateException("Only a RECEIVED purchase order can be closed");
        }
        this.status = PurchaseOrderStatus.CLOSED;
        touch();
    }

    public boolean isReceivable() {
        return status == PurchaseOrderStatus.SUBMITTED || status == PurchaseOrderStatus.PARTIALLY_RECEIVED;
    }

    public PurchaseOrderLine lineFor(UUID ingredientId) {
        return lines.stream().filter(l -> l.ingredientId().equals(ingredientId)).findFirst().orElse(null);
    }

    private void touch() {
        this.updatedAt = Instant.now();
    }

    public UUID id() {
        return id;
    }

    public String number() {
        return number;
    }

    public UUID storeId() {
        return storeId;
    }

    public UUID supplierId() {
        return supplierId;
    }

    public PurchaseOrderStatus status() {
        return status;
    }

    public Instant orderedAt() {
        return orderedAt;
    }

    public Instant expectedAt() {
        return expectedAt;
    }

    public String notes() {
        return notes;
    }

    public UUID createdByUserId() {
        return createdByUserId;
    }

    public List<PurchaseOrderLine> lines() {
        return List.copyOf(lines);
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
