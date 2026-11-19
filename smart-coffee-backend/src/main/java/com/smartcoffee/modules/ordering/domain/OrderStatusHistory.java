package com.smartcoffee.modules.ordering.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "ordering", name = "order_status_history")
public class OrderStatusHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "history_id")
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id", nullable = false)
    private Order order;
    @Enumerated(EnumType.STRING)
    @Column(name = "from_status")
    private OrderStatus fromStatus;
    @Enumerated(EnumType.STRING)
    @Column(name = "to_status", nullable = false)
    private OrderStatus toStatus;
    @Column(name = "changed_by_user_id")
    private UUID actor;
    @Column(columnDefinition = "text")
    private String reason;
    @Column(name = "changed_at", nullable = false)
    private Instant changedAt;

    protected OrderStatusHistory() {
    }

    OrderStatusHistory(Order o, OrderStatus from, OrderStatus to, UUID actor, String reason) {
        order = o;
        fromStatus = from;
        toStatus = to;
        this.actor = actor;
        this.reason = reason;
        changedAt = Instant.now();
    }
}

