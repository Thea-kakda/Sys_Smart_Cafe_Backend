package com.smartcoffee.modules.inventory.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.*;

@Entity
@Table(schema = "inventory", name = "stock_transactions")
public class StockTransaction {
    @Id
    @Column(name = "stock_transaction_id")
    private UUID id;
    @Column(name = "transaction_number", nullable = false, unique = true)
    private String number;
    @Column(name = "store_id", nullable = false)
    private UUID storeId;
    @Enumerated(EnumType.STRING)
    @Column(name = "transaction_type", nullable = false)
    private StockTransactionType type;
    @Column(name = "reference_type")
    private String referenceType;
    @Column(name = "reference_id")
    private UUID referenceId;
    @Column(name = "recorded_by_user_id")
    private UUID actorId;
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
    // Operator explanation for manual writes (V030); null for system-generated
    // ledger rows such as SALE_CONSUMPTION.
    @Column(name = "reason", length = 500)
    private String reason;
    // Cart traceability (V045): a SALE_RESERVATION / SALE_RELEASE row names the
    // cart line and the reservation whose held quantity it moved.
    @Column(name = "cart_id")
    private UUID cartId;
    @Column(name = "cart_item_id")
    private UUID cartItemId;
    @Column(name = "reservation_id")
    private UUID reservationId;
    @Column(name = "idempotency_key", length = 200)
    private String idempotencyKey;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @OneToMany(mappedBy = "transaction", cascade = CascadeType.ALL)
    private List<StockTransactionLine> lines = new ArrayList<>();

    protected StockTransaction() {
    }

    public static StockTransaction create(UUID store, StockTransactionType type, String refType, UUID ref, UUID actor,
            List<StockTransactionLine> lines, String reason) {
        return create(store, type, refType, ref, actor, lines, reason, null, null, null, null);
    }

    /**
     * Reservation-aware ledger row (Slice 2C). A cart reservation write moves the
     * <b>reserved</b> balance only; {@code cartId}/{@code cartItemId}/
     * {@code reservationId} make the move attributable without a join.
     */
    public static StockTransaction create(UUID store, StockTransactionType type, String refType, UUID ref, UUID actor,
            List<StockTransactionLine> lines, String reason, UUID cartId, UUID cartItemId, UUID reservationId,
            String idempotencyKey) {
        var t = new StockTransaction();
        t.id = UUID.randomUUID();
        t.number = "STK-" + t.id;
        t.storeId = store;
        t.type = type;
        t.referenceType = refType;
        t.referenceId = ref;
        t.actorId = actor;
        t.occurredAt = Instant.now();
        t.createdAt = t.occurredAt;
        t.reason = reason;
        t.cartId = cartId;
        t.cartItemId = cartItemId;
        t.reservationId = reservationId;
        t.idempotencyKey = idempotencyKey;
        lines.forEach(x -> {
            x.attach(t);
            t.lines.add(x);
        });
        return t;
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

    public StockTransactionType type() {
        return type;
    }

    public String reason() {
        return reason;
    }

    public Instant occurredAt() {
        return occurredAt;
    }
}

