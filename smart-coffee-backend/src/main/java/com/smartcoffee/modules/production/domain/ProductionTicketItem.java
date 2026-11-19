package com.smartcoffee.modules.production.domain;

import com.smartcoffee.common.exception.ConflictException;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "production", name = "production_ticket_items", uniqueConstraints = @UniqueConstraint(columnNames = {
        "ticket_id", "order_item_id" }))
public class ProductionTicketItem {
    @Id
    @Column(name = "ticket_item_id")
    private UUID id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "ticket_id", nullable = false)
    private ProductionTicket ticket;
    @Column(name = "order_item_id", nullable = false)
    private UUID orderItemId;
    @Column(name = "product_name_snapshot", nullable = false)
    private String productName;
    @Column(nullable = false)
    private int quantity;
    @Column(name = "customization_summary")
    private String customizationSummary;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProductionStatus status;
    @Column(name = "assigned_user_id")
    private UUID assignedUserId;
    @Column(name = "started_at")
    private Instant startedAt;
    @Column(name = "ready_at")
    private Instant readyAt;
    @Version
    private long version;

    protected ProductionTicketItem() {
    }

    public ProductionTicketItem(UUID orderItem, String product, int quantity, String customization) {
        id = UUID.randomUUID();
        orderItemId = orderItem;
        productName = product;
        this.quantity = quantity;
        customizationSummary = customization;
        status = ProductionStatus.PENDING;
    }

    void attach(ProductionTicket t) {
        ticket = t;
    }

    public void start(UUID actor) {
        if (status != ProductionStatus.PENDING)
            throw new ConflictException("PRODUCTION_STATE_CONFLICT", "Item is not pending");
        status = ProductionStatus.PREPARING;
        assignedUserId = actor;
        startedAt = Instant.now();
    }

    public void ready(UUID actor) {
        if (status != ProductionStatus.PREPARING)
            throw new ConflictException("PRODUCTION_STATE_CONFLICT", "Item is not preparing");
        status = ProductionStatus.READY;
        assignedUserId = actor;
        readyAt = Instant.now();
    }

    /**
     * Cancels outstanding work on this item. Items that already finished
     * (READY/COMPLETED) are left untouched so produced goods are not rewritten
     * as cancelled; only pending/in-progress work transitions to CANCELLED.
     * Cancelling an already-cancelled item is a no-op (idempotent replay).
     */
    public void cancel() {
        if (status == ProductionStatus.READY || status == ProductionStatus.COMPLETED
                || status == ProductionStatus.CANCELLED)
            return;
        status = ProductionStatus.CANCELLED;
    }

    public UUID id() {
        return id;
    }

    public UUID orderItemId() {
        return orderItemId;
    }

    public String productName() {
        return productName;
    }

    public int quantity() {
        return quantity;
    }

    public String customizationSummary() {
        return customizationSummary;
    }

    public ProductionStatus status() {
        return status;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public Instant readyAt() {
        return readyAt;
    }

    public long version() {
        return version;
    }
}

