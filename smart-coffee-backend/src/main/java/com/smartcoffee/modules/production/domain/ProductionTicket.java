package com.smartcoffee.modules.production.domain;

// import com.smartcoffee.common.exception.ConflictException;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(schema = "production", name = "production_tickets", uniqueConstraints = @UniqueConstraint(columnNames = {
        "order_id", "station_id" }))
public class ProductionTicket {
    @Id
    @Column(name = "ticket_id")
    private UUID id;
    @Column(name = "order_id", nullable = false)
    private UUID orderId;
    @Column(name = "store_id", nullable = false)
    private UUID storeId;
    @Column(name = "station_id", nullable = false)
    private UUID stationId;
    @Column(name = "ticket_number", nullable = false, unique = true)
    private String number;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProductionStatus status;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderPriority priority;
    @Column(name = "queued_at", nullable = false)
    private Instant queuedAt;
    @Column(name = "started_at")
    private Instant startedAt;
    @Column(name = "ready_at")
    private Instant readyAt;
    @Column(name = "completed_at")
    private Instant completedAt;
    @Version
    private long version;
    @OneToMany(mappedBy = "ticket", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<ProductionTicketItem> items = new ArrayList<>();

    protected ProductionTicket() {
    }

    public static ProductionTicket create(UUID order, UUID store, UUID station, String no,
            List<ProductionTicketItem> items) {
        var t = new ProductionTicket();
        t.id = UUID.randomUUID();
        t.orderId = order;
        t.storeId = store;
        t.stationId = station;
        t.number = no;
        t.status = ProductionStatus.PENDING;
        t.priority = OrderPriority.NORMAL;
        t.queuedAt = Instant.now();
        items.forEach(i -> {
            i.attach(t);
            t.items.add(i);
        });
        return t;
    }

    public void recalculate() {
        if (items.stream()
                .allMatch(i -> i.status() == ProductionStatus.READY || i.status() == ProductionStatus.COMPLETED)) {
            status = ProductionStatus.READY;
            readyAt = Instant.now();
        } else if (items.stream().anyMatch(i -> i.status() == ProductionStatus.PREPARING)) {
            status = ProductionStatus.PREPARING;
            if (startedAt == null)
                startedAt = Instant.now();
        }
    }

    /**
     * Cancels this ticket and its outstanding items after the owning order is
     * cancelled. Returns {@code false} when the ticket was already cancelled so
     * callers can treat a redelivered cancellation event as a no-op replay.
     */
    @SuppressWarnings("null") // items is the non-null aggregate collection
    public boolean cancel() {
        if (status == ProductionStatus.CANCELLED)
            return false;
        status = ProductionStatus.CANCELLED;
        items.forEach(ProductionTicketItem::cancel);
        return true;
    }

    public UUID id() {
        return id;
    }

    public UUID orderId() {
        return orderId;
    }

    public UUID storeId() {
        return storeId;
    }

    public UUID stationId() {
        return stationId;
    }

    public String number() {
        return number;
    }

    public ProductionStatus status() {
        return status;
    }

    public OrderPriority priority() {
        return priority;
    }

    public Instant queuedAt() {
        return queuedAt;
    }

    public List<ProductionTicketItem> items() {
        return List.copyOf(items);
    }

    public long version() {
        return version;
    }
}

