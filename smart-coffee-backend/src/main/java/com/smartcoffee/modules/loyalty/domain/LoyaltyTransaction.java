package com.smartcoffee.modules.loyalty.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(schema = "loyalty", name = "loyalty_transactions")
public class LoyaltyTransaction {
    @Id
    @Column(name = "loyalty_transaction_id")
    private UUID id;
    @Column(name = "loyalty_account_id", nullable = false)
    private UUID accountId;
    @Column(name = "transaction_number", nullable = false, unique = true)
    private String number;
    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false)
    private LoyaltyTransactionType type;
    @Column(name = "points_delta", nullable = false)
    private long delta;
    @Column(name = "balance_before", nullable = false)
    private long before;
    @Column(name = "balance_after", nullable = false)
    private long after;
    @Column(name = "order_id")
    private UUID orderId;
    @Column(name = "source_event_id")
    private UUID sourceEventId;
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    protected LoyaltyTransaction() {
    }

    public static LoyaltyTransaction create(UUID account, LoyaltyTransactionType type, long delta, long before,
            long after, UUID order, UUID event) {
        var t = new LoyaltyTransaction();
        t.id = UUID.randomUUID();
        t.number = "LOY-" + t.id;
        t.accountId = account;
        t.type = type;
        t.delta = delta;
        t.before = before;
        t.after = after;
        t.orderId = order;
        t.sourceEventId = event;
        t.occurredAt = Instant.now();
        return t;
    }
}

