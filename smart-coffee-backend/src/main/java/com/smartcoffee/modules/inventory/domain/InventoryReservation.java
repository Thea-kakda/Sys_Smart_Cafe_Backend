package com.smartcoffee.modules.inventory.domain;

import com.smartcoffee.common.exception.ConflictException;
import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

/**
 * Stock held for an owner. Two ownership shapes share ONE table (V045):
 *
 * <ul>
 *   <li><b>order-owned</b> — created at checkout ({@code order_id} unique, 15
 *       minute TTL). Since Slice 2D it is produced by converting the cart's
 *       persisted snapshot instead of recalculating demand, and it is consumed
 *       when the payment settles, not when production starts;</li>
 *   <li><b>cart-owned</b> — created when a recipe-tracked cart line is added
 *       (Slice 2C): {@code cart_id}/{@code cart_item_id} set, no order yet. The
 *       row is mutated in place by the reservation delta engine, so a cart line
 *       never accumulates more than one active reservation, and
 *       {@code demandRevision} records how many times its held demand moved.</li>
 * </ul>
 *
 * <p>Released history is preserved: rows are never hard-deleted, they move to a
 * terminal status and keep their {@code release_reason}.
 */
@Entity
@Table(schema = "inventory", name = "inventory_reservations")
public class InventoryReservation {
    @Id
    @Column(name = "reservation_id")
    private UUID id;
    @Column(name = "store_id", nullable = false)
    private UUID storeId;
    /** Order owner (checkout). Null for cart-owned reservations. */
    @Column(name = "order_id")
    private UUID orderId;
    /** Cart owner (pre-checkout, Slice 2C). Null for order-owned reservations. */
    @Column(name = "cart_id")
    private UUID cartId;
    /** The cart line this reservation is attributable to; never reset. */
    @Column(name = "cart_item_id")
    private UUID cartItemId;
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReservationStatus status;
    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;
    @Column(name = "consumed_at")
    private Instant consumedAt;
    @Column(name = "released_at")
    private Instant releasedAt;
    @Column(name = "expired_at")
    private Instant expiredAt;
    /** Monotonic revision of the held demand map (starts at 1, bumps on delta). */
    @Column(name = "demand_revision", nullable = false)
    private long demandRevision = 1;
    /** ORDER or CART_ITEM — who owns this reservation. */
    @Column(name = "source_type", nullable = false, length = 30)
    private String sourceType = "ORDER";
    /** Demand snapshot provenance; release never re-reads the catalog. */
    @Column(name = "source_recipe_version_id")
    private UUID sourceRecipeVersionId;
    @Column(name = "source_recipe_version_number")
    private Integer sourceRecipeVersionNumber;
    @Column(name = "resolution_source", length = 20)
    private String resolutionSource;
    @Column(name = "idempotency_key", length = 200)
    private String idempotencyKey;
    @Column(name = "release_reason", length = 60)
    private String releaseReason;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
    /** When a cart-owned hold was converted into an order reservation (V046). */
    @Column(name = "converted_at")
    private Instant convertedAt;
    /** The order that took the demand over (cart-owned rows only). */
    @Column(name = "converted_order_id")
    private UUID convertedOrderId;
    /** The order line fed by this cart line; one line per converted hold. */
    @Column(name = "converted_order_line_id")
    private UUID convertedOrderLineId;
    /** The single order reservation that received this cart line's demand. */
    @Column(name = "converted_to_reservation_id")
    private UUID convertedToReservationId;
    /** Actor credited with consuming an order reservation (V046). */
    @Column(name = "consumed_by")
    private UUID consumedBy;
    // ---- Expiry-worker claim metadata (Slice 2E, V047). Written only by the
    // ---- multi-node claim SQL (FOR UPDATE SKIP LOCKED), never by the domain,
    // ---- so they are mapped read-only: the reservation's own @Version stays the
    // ---- sole optimistic-lock token and a claim touch never bumps it.
    @Column(name = "processing_started_at", insertable = false, updatable = false)
    private Instant processingStartedAt;
    @Column(name = "processing_owner", insertable = false, updatable = false)
    private String processingOwner;
    @Column(name = "processing_token", insertable = false, updatable = false)
    private String processingToken;
    @Column(name = "last_processing_error", insertable = false, updatable = false)
    private String lastProcessingError;
    @Column(name = "processing_attempts", insertable = false, updatable = false)
    private int processingAttempts;
    @Column(name = "last_reconciled_at", insertable = false, updatable = false)
    private Instant lastReconciledAt;
    @Column(name = "reconciliation_status", insertable = false, updatable = false)
    private String reconciliationStatus;
    @Version
    private long version;
    @OneToMany(mappedBy = "reservation", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<InventoryReservationLine> lines = new ArrayList<>();

    protected InventoryReservation() {
    }

    /** Order-owned reservation (checkout / Slice 2A): unchanged contract. */
    public static InventoryReservation create(UUID store, UUID order, Instant expiry,
            List<InventoryReservationLine> lines) {
        var r = new InventoryReservation();
        r.id = UUID.randomUUID();
        r.storeId = store;
        r.orderId = order;
        r.sourceType = "ORDER";
        r.status = ReservationStatus.ACTIVE;
        r.expiresAt = expiry;
        r.createdAt = Instant.now();
        r.updatedAt = r.createdAt;
        lines.forEach(x -> {
            x.attach(r);
            r.lines.add(x);
        });
        return r;
    }

    /**
     * Cart-owned reservation holding the authoritative consumption plan of one
     * cart line. The demand map is the persisted snapshot: releases use it and
     * never recompute from the current recipe/option definitions.
     */
    public static InventoryReservation forCart(UUID store, UUID cart, UUID cartItem, Instant expiry,
            Map<UUID, BigDecimal> demand, Map<UUID, String> baseUnits, UUID recipeVersionId,
            Integer recipeVersionNumber, String resolutionSource, String idempotencyKey) {
        var r = new InventoryReservation();
        r.id = UUID.randomUUID();
        r.storeId = store;
        r.cartId = cart;
        r.cartItemId = cartItem;
        r.sourceType = "CART_ITEM";
        r.status = ReservationStatus.ACTIVE;
        r.expiresAt = expiry;
        r.demandRevision = 1;
        r.sourceRecipeVersionId = recipeVersionId;
        r.sourceRecipeVersionNumber = recipeVersionNumber;
        r.resolutionSource = resolutionSource;
        r.idempotencyKey = idempotencyKey;
        r.createdAt = Instant.now();
        r.updatedAt = r.createdAt;
        // Deterministic line order: sorted ingredient ids, never map iteration order.
        for (Map.Entry<UUID, BigDecimal> entry : new TreeMap<>(demand).entrySet()) {
            InventoryReservationLine line = new InventoryReservationLine(entry.getKey(), entry.getValue(),
                    baseUnits.get(entry.getKey()));
            line.attach(r);
            r.lines.add(line);
        }
        return r;
    }

    private void active() {
        if (status != ReservationStatus.ACTIVE)
            throw new ConflictException("RESERVATION_STATE_CONFLICT", "Reservation is not active");
    }

    /**
     * Converts a cart-owned hold into the order's reservation (Slice 2D).
     *
     * <p>This is a <b>metadata-only</b> ownership transition: the held lines are
     * not touched, no balance moves and no ledger row is written, so the stock
     * is never observable as available in between. The cart row survives as
     * provenance (it names the original cart line, its demand revision and its
     * recipe version) and can never be released or consumed again.
     */
    public void converted(UUID order, UUID orderLine, UUID orderReservation, Instant expiry) {
        active();
        if (sourceType == null || !"CART_ITEM".equals(sourceType))
            throw new ConflictException("RESERVATION_STATE_CONFLICT", "Only a cart hold can be converted");
        status = ReservationStatus.CONVERTED;
        convertedOrderId = order;
        convertedOrderLineId = orderLine;
        convertedToReservationId = orderReservation;
        convertedAt = updatedAt = Instant.now();
        if (expiry != null && expiry.isAfter(createdAt)) {
            expiresAt = expiry;
        }
    }

    /**
     * Order-owned reservation that takes over the persisted cart demand. Same
     * snapshot, one row per order ({@code order_id} is unique), no balance move:
     * the caller only reserves the part that no cart line held yet.
     */
    public static InventoryReservation fromCartHolds(UUID store, UUID order, UUID cart, Instant expiry,
            Map<UUID, BigDecimal> demand, Map<UUID, String> baseUnits, String idempotencyKey) {
        var r = new InventoryReservation();
        r.id = UUID.randomUUID();
        r.storeId = store;
        r.orderId = order;
        r.cartId = cart;
        r.sourceType = "ORDER";
        r.status = ReservationStatus.ACTIVE;
        r.expiresAt = expiry;
        r.idempotencyKey = idempotencyKey;
        r.createdAt = Instant.now();
        r.updatedAt = r.createdAt;
        for (Map.Entry<UUID, BigDecimal> entry : new TreeMap<>(demand).entrySet()) {
            InventoryReservationLine line = new InventoryReservationLine(entry.getKey(), entry.getValue(),
                    baseUnits.get(entry.getKey()));
            line.attach(r);
            r.lines.add(line);
        }
        return r;
    }

    public void consumed() {
        consumed(null);
    }

    /** Terminal settlement transition; {@code actor} is the credited user. */
    public void consumed(UUID actor) {
        active();
        status = ReservationStatus.CONSUMED;
        consumedBy = actor;
        consumedAt = updatedAt = Instant.now();
    }

    public void released() {
        released(null);
    }

    /** Terminal transition; {@code reason} is the audited release cause. */
    public void released(String reason) {
        active();
        status = ReservationStatus.RELEASED;
        releaseReason = reason;
        releasedAt = updatedAt = Instant.now();
    }

    public void cancelled(String reason) {
        active();
        status = ReservationStatus.CANCELLED;
        releaseReason = reason;
        releasedAt = updatedAt = Instant.now();
    }

    public void expired() {
        active();
        status = ReservationStatus.EXPIRED;
        expiredAt = updatedAt = Instant.now();
    }

    /** Holds more of an ingredient for this owner (delta engine, positive side). */
    public void increase(UUID ingredient, BigDecimal quantity, String baseUnitCode) {
        active();
        for (InventoryReservationLine line : lines) {
            if (line.ingredientId().equals(ingredient)) {
                line.add(quantity);
                updatedAt = Instant.now();
                return;
            }
        }
        InventoryReservationLine line = new InventoryReservationLine(ingredient, quantity, baseUnitCode);
        line.attach(this);
        lines.add(line);
        updatedAt = Instant.now();
    }

    /** Gives held quantity back (delta engine, negative side). Never goes below zero. */
    public void decrease(UUID ingredient, BigDecimal quantity) {
        active();
        Iterator<InventoryReservationLine> it = lines.iterator();
        while (it.hasNext()) {
            InventoryReservationLine line = it.next();
            if (!line.ingredientId().equals(ingredient)) {
                continue;
            }
            BigDecimal remaining = line.quantityReserved().subtract(quantity);
            if (remaining.signum() < 0) {
                throw new ConflictException("INVALID_RESERVATION_DELTA",
                        "Release exceeds the quantity held for ingredient " + ingredient);
            }
            if (remaining.signum() == 0) {
                it.remove();
            } else {
                line.quantityReserved(remaining);
            }
            updatedAt = Instant.now();
            return;
        }
        throw new ConflictException("RESERVATION_CONFLICT",
                "No held quantity for ingredient " + ingredient + " in reservation " + id);
    }

    /** Re-stamps the demand snapshot provenance after a configuration change. */
    public void revise(UUID recipeVersionId, Integer recipeVersionNumber, String source, Instant expiry) {
        sourceRecipeVersionId = recipeVersionId;
        sourceRecipeVersionNumber = recipeVersionNumber;
        resolutionSource = source;
        if (expiry != null) {
            expiresAt = expiry;
        }
        demandRevision += 1;
        updatedAt = Instant.now();
    }

    /** Persisted demand snapshot (ingredient → base-unit quantity). */
    public Map<UUID, BigDecimal> demandSnapshot() {
        Map<UUID, BigDecimal> snapshot = new TreeMap<>();
        for (InventoryReservationLine line : lines) {
            snapshot.merge(line.ingredientId(), line.quantityReserved(), (left, right) -> left.add(right));
        }
        return snapshot;
    }

    public UUID id() {
        return id;
    }

    public UUID storeId() {
        return storeId;
    }

    public UUID orderId() {
        return orderId;
    }

    public UUID cartId() {
        return cartId;
    }

    public UUID cartItemId() {
        return cartItemId;
    }

    public ReservationStatus status() {
        return status;
    }

    public String sourceType() {
        return sourceType;
    }

    public long demandRevision() {
        return demandRevision;
    }

    public UUID sourceRecipeVersionId() {
        return sourceRecipeVersionId;
    }

    public Integer sourceRecipeVersionNumber() {
        return sourceRecipeVersionNumber;
    }

    public String releaseReason() {
        return releaseReason;
    }

    public Instant convertedAt() {
        return convertedAt;
    }

    public UUID convertedOrderId() {
        return convertedOrderId;
    }

    public UUID convertedOrderLineId() {
        return convertedOrderLineId;
    }

    public UUID convertedToReservationId() {
        return convertedToReservationId;
    }

    public UUID consumedBy() {
        return consumedBy;
    }

    public Instant consumedAt() {
        return consumedAt;
    }

    public Instant releasedAt() {
        return releasedAt;
    }

    public String resolutionSource() {
        return resolutionSource;
    }

    public String idempotencyKey() {
        return idempotencyKey;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    /** Optimistic-lock token; exposed so an administrative release can require an expectedVersion. */
    public long version() {
        return version;
    }

    public Instant processingStartedAt() {
        return processingStartedAt;
    }

    public String processingOwner() {
        return processingOwner;
    }

    public String processingToken() {
        return processingToken;
    }

    public String lastProcessingError() {
        return lastProcessingError;
    }

    public int processingAttempts() {
        return processingAttempts;
    }

    public Instant lastReconciledAt() {
        return lastReconciledAt;
    }

    public String reconciliationStatus() {
        return reconciliationStatus;
    }

    public List<InventoryReservationLine> lines() {
        return List.copyOf(lines);
    }
}
