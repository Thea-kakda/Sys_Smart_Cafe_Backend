package com.smartcoffee.modules.inventory.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Goods receipt header (V033): a physical intake against a purchase order for a
 * store. Owns its lines (cascade). Immutability-oriented — a receipt is created
 * complete when the goods arrive and is never edited afterwards; corrections go
 * through a fresh adjustment or a new receipt. The stock effect is realized in
 * the service via the guarded balance mutations + one ledger transaction; this
 * entity is the auditable record of who received what, when.
 */
@Entity
@Table(schema = "inventory", name = "goods_receipts")
public class GoodsReceipt {
    @Id
    @Column(name = "goods_receipt_id")
    private UUID id;
    @Column(name = "receipt_number", nullable = false, unique = true)
    private String number;
    @Column(name = "purchase_order_id", nullable = false)
    private UUID purchaseOrderId;
    @Column(name = "store_id", nullable = false)
    private UUID storeId;
    @Column(name = "received_by_user_id")
    private UUID receivedByUserId;
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;
    @OneToMany(mappedBy = "receipt", cascade = CascadeType.ALL)
    private List<GoodsReceiptLine> lines = new ArrayList<>();
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Version
    private long version;

    protected GoodsReceipt() {
    }

    public static GoodsReceipt record(UUID purchaseOrderId, UUID storeId, UUID receivedBy,
            List<GoodsReceiptLine> lines) {
        if (purchaseOrderId == null) {
            throw new IllegalArgumentException("Purchase order is required");
        }
        if (storeId == null) {
            throw new IllegalArgumentException("Store is required");
        }
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("A goods receipt needs at least one line");
        }
        var r = new GoodsReceipt();
        r.id = UUID.randomUUID();
        r.number = "GR-" + r.id;
        r.purchaseOrderId = purchaseOrderId;
        r.storeId = storeId;
        r.receivedByUserId = receivedBy;
        var now = Instant.now();
        r.receivedAt = now;
        r.createdAt = now;
        r.lines = new ArrayList<>();
        lines.forEach(l -> {
            l.attach(r);
            r.lines.add(l);
        });
        return r;
    }

    public UUID id() {
        return id;
    }

    public String number() {
        return number;
    }

    public UUID purchaseOrderId() {
        return purchaseOrderId;
    }

    public UUID storeId() {
        return storeId;
    }

    public UUID receivedByUserId() {
        return receivedByUserId;
    }

    public Instant receivedAt() {
        return receivedAt;
    }

    public List<GoodsReceiptLine> lines() {
        return List.copyOf(lines);
    }

    public long version() {
        return version;
    }
}
