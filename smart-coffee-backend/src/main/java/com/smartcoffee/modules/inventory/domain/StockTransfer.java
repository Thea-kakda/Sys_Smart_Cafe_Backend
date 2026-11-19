package com.smartcoffee.modules.inventory.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Inter-branch stock transfer header (V035). Moves physical ingredients from a
 * SOURCE store to a DEST store, both within the actor's scope. Owns its lines
 * (cascade) and drives the status machine: a REQUESTED transfer can be
 * dispatched (stock leaves the source, IN_TRANSIT) and then received (stock
 * arrives at the dest, RECEIVED); it can be CANCELLED before receipt. As with
 * purchase orders there are no hard deletes — cancel is the only retirement
 * (A3). Optimistic locking via {@code @Version}; the transfer number is
 * generated here. The distinct-stores rule is also a DB CHECK
 * ({@code ck_transfer_distinct_stores}).
 */
@Entity
@Table(schema = "inventory", name = "stock_transfers")
public class StockTransfer {
    @Id
    @Column(name = "transfer_id")
    private UUID id;
    @Column(name = "transfer_number", nullable = false, unique = true)
    private String number;
    @Column(name = "source_store_id", nullable = false)
    private UUID sourceStoreId;
    @Column(name = "dest_store_id", nullable = false)
    private UUID destStoreId;
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private StockTransferStatus status;
    @Column(length = 1000)
    private String notes;
    @Column(name = "requested_by_user_id")
    private UUID requestedByUserId;
    @Column(name = "dispatched_at")
    private Instant dispatchedAt;
    @Column(name = "received_at")
    private Instant receivedAt;
    @OneToMany(mappedBy = "transfer", cascade = CascadeType.ALL, orphanRemoval = false)
    private List<StockTransferLine> lines = new ArrayList<>();
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    @Version
    private long version;

    protected StockTransfer() {
    }

    public static StockTransfer request(UUID sourceStoreId, UUID destStoreId, String notes, UUID requestedBy,
            List<StockTransferLine> lines) {
        if (sourceStoreId == null || destStoreId == null) {
            throw new IllegalArgumentException("Source and destination stores are required");
        }
        if (sourceStoreId.equals(destStoreId)) {
            throw new IllegalArgumentException("Source and destination stores must differ");
        }
        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("A transfer needs at least one line");
        }
        var t = new StockTransfer();
        t.id = UUID.randomUUID();
        t.number = "TR-" + t.id;
        t.sourceStoreId = sourceStoreId;
        t.destStoreId = destStoreId;
        t.status = StockTransferStatus.REQUESTED;
        t.notes = notes == null || notes.isBlank() ? null : notes.trim();
        t.requestedByUserId = requestedBy;
        var now = Instant.now();
        t.lines = new ArrayList<>();
        lines.forEach(l -> {
            l.attach(t);
            t.lines.add(l);
        });
        t.createdAt = now;
        t.updatedAt = now;
        return t;
    }

    /** REQUESTED -> IN_TRANSIT (source stock has physically left). */
    public void dispatch() {
        if (status != StockTransferStatus.REQUESTED) {
            throw new IllegalStateException("Only a REQUESTED transfer can be dispatched");
        }
        this.status = StockTransferStatus.IN_TRANSIT;
        this.dispatchedAt = Instant.now();
        touch();
    }

    /** IN_TRANSIT -> RECEIVED (dest stock has physically arrived). */
    public void receive() {
        if (status != StockTransferStatus.IN_TRANSIT) {
            throw new IllegalStateException("Only an IN_TRANSIT transfer can be received");
        }
        this.status = StockTransferStatus.RECEIVED;
        this.receivedAt = Instant.now();
        touch();
    }

    /** REQUESTED or IN_TRANSIT (pre-receipt) -> CANCELLED. */
    public void cancel() {
        if (status != StockTransferStatus.REQUESTED && status != StockTransferStatus.IN_TRANSIT) {
            throw new IllegalStateException("Only an unreceived transfer can be cancelled");
        }
        this.status = StockTransferStatus.CANCELLED;
        touch();
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

    public UUID sourceStoreId() {
        return sourceStoreId;
    }

    public UUID destStoreId() {
        return destStoreId;
    }

    public StockTransferStatus status() {
        return status;
    }

    public String notes() {
        return notes;
    }

    public UUID requestedByUserId() {
        return requestedByUserId;
    }

    public Instant dispatchedAt() {
        return dispatchedAt;
    }

    public Instant receivedAt() {
        return receivedAt;
    }

    public List<StockTransferLine> lines() {
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
