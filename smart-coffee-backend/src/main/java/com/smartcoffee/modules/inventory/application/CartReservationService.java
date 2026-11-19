package com.smartcoffee.modules.inventory.application;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.modules.inventory.domain.InventoryBalance;
import com.smartcoffee.modules.inventory.domain.InventoryBalanceId;
import com.smartcoffee.modules.inventory.domain.InventoryReservation;
import com.smartcoffee.modules.inventory.domain.InventoryReservationLine;
import com.smartcoffee.modules.inventory.domain.ReservationStatus;
import com.smartcoffee.modules.inventory.domain.StockTransaction;
import com.smartcoffee.modules.inventory.domain.StockTransactionLine;
import com.smartcoffee.modules.inventory.domain.StockTransactionType;
import com.smartcoffee.modules.inventory.persistence.InventoryBalanceRepository;
import com.smartcoffee.modules.inventory.persistence.InventoryReservationRepository;
import com.smartcoffee.modules.inventory.persistence.StockTransactionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.SortedMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Cart-owned inventory reservations (Slice 2C).
 *
 * <p>Reserves the authoritative {@link ReservationDemand} of a cart line at add
 * time and then moves it by <b>delta only</b> on every quantity or configuration
 * change, so a small edit never releases and re-reserves the whole line.
 *
 * <p>Invariants enforced here:
 * <ul>
 *   <li>{@code quantityOnHand} (current_stock) is never touched — only
 *       {@code reserved_stock}; every change is a guarded conditional update in
 *       {@link InventoryBalanceRepository}, which is what makes the last-stock
 *       race safe without extra locking.</li>
 *   <li>Balance rows are visited in ascending inventory-item id order, for both
 *       reserve and release, so two carts with overlapping ingredients cannot
 *       deadlock.</li>
 *   <li>One reservation row per cart line ({@code uk_reservation_cart_item}); it
 *       is mutated in place and its {@code demand_revision} is bumped.</li>
 *   <li>Each move writes exactly one {@code SALE_RESERVATION} or
 *       {@code SALE_RELEASE} ledger transaction carrying cart, cart-item,
 *       reservation and idempotency references.</li>
 *   <li>Releasing an already terminal reservation is a silent no-op, so remove,
 *       clear and expiry are idempotent by construction.</li>
 * </ul>
 *
 * <p>Everything runs inside the caller's transaction: cart row, cart line,
 * reservation lines, balances and ledger commit together or not at all.
 */
@Service
public class CartReservationService {

    private static final Logger log = LoggerFactory.getLogger(CartReservationService.class);
    private static final String CART_REFERENCE = "CART";

    private final InventoryBalanceRepository balances;
    private final InventoryReservationRepository reservations;
    private final StockTransactionRepository ledger;

    public CartReservationService(InventoryBalanceRepository balances, InventoryReservationRepository reservations,
            StockTransactionRepository ledger) {
        this.balances = balances;
        this.reservations = reservations;
        this.ledger = ledger;
    }

    /**
     * Reserves (no existing hold) or re-aligns (existing hold) the demand of one
     * cart line with a per-ingredient delta.
     *
     * @param operation      audited cause, see {@link ReservationReasons}
     * @param idempotencyKey client/server idempotency identity, nullable
     * @return the reservation id, or null when nothing had to be held
     */
    @Transactional
    public UUID apply(UUID storeId, UUID cartId, UUID cartItemId, ReservationDemand demand,
            Instant expiresAt, UUID actor, String operation, String idempotencyKey) {
        if (storeId == null || cartId == null || cartItemId == null) {
            throw new ValidationException("CART_ITEM_NOT_FOUND", "Cart, store and cart item are required");
        }
        ReservationDemand wanted = demand == null ? ReservationDemand.empty() : demand;
        Optional<InventoryReservation> existing = reservations.lockByCartItemId(cartItemId);
        if (existing.isEmpty()) {
            if (wanted.isEmpty()) {
                return null;
            }
            return create(storeId, cartId, cartItemId, wanted, expiresAt, actor, operation, idempotencyKey);
        }
        InventoryReservation reservation = existing.get();
        if (reservation.status() != ReservationStatus.ACTIVE) {
            if (wanted.isEmpty()) {
                return reservation.id();
            }
            throw new ConflictException("RESERVATION_CONFLICT",
                    "This cart line's reservation is " + reservation.status().name() + " and cannot be re-opened");
        }
        return applyDelta(reservation, wanted, expiresAt, actor, operation, idempotencyKey);
    }

    /** Gives back everything one cart line holds. Idempotent. */
    @Transactional
    public void releaseItem(UUID cartItemId, String reason, UUID actor) {
        if (cartItemId == null) {
            return;
        }
        reservations.lockByCartItemId(cartItemId).ifPresent(r -> releaseHeld(r, reason, actor));
    }

    /** Gives back every ACTIVE hold of one cart. Idempotent; the caller locks the cart row. */
    @Transactional
    public void releaseCart(UUID cartId, String reason, UUID actor) {
        if (cartId == null) {
            return;
        }
        for (InventoryReservation reservation : reservations.findActiveByCartId(cartId)) {
            releaseHeld(reservation, reason, actor);
        }
    }

    /** Persisted snapshot of one cart line's hold; empty when the line never reserved. */
    @Transactional(readOnly = true)
    public Optional<ReservationSnapshot> snapshot(UUID cartItemId) {
        return reservations.findByCartItemId(cartItemId).map(r -> new ReservationSnapshot(r.id(), r.status(),
                r.demandRevision(), r.demandSnapshot()));
    }

    /** Read-only view of a reservation as the API/tests need it (no ledger detail). */
    public record ReservationSnapshot(UUID reservationId, ReservationStatus status, long demandRevision,
            Map<UUID, BigDecimal> held) {
    }

    // ------------------------------------------------------------- internals

    private UUID create(UUID storeId, UUID cartId, UUID cartItemId, ReservationDemand demand, Instant expiresAt,
            UUID actor, String operation, String idempotencyKey) {
        List<StockTransactionLine> moves = new ArrayList<>();
        for (Map.Entry<UUID, BigDecimal> entry : demand.lines().entrySet()) {
            UUID item = entry.getKey();
            BigDecimal quantity = entry.getValue();
            InventoryBalance balance = requireBalance(storeId, item);
            BigDecimal before = balance.reservedStock();
            if (balances.reserve(storeId, item, quantity) != 1) {
                throw insufficientStock(item, quantity, balance);
            }
            moves.add(new StockTransactionLine(item, quantity, null, before, before.add(quantity)));
        }
        InventoryReservation reservation = InventoryReservation.forCart(storeId, cartId, cartItemId, expiresAt,
                demand.lines(), demand.baseUnits(), demand.recipeVersionId(), demand.recipeVersionNumber(),
                demand.resolutionSource(), idempotencyKey);
        // Flushed first: the ledger row carries a reservation_id foreign key.
        reservations.saveAndFlush(reservation);
        ledger.save(StockTransaction.create(storeId, StockTransactionType.SALE_RESERVATION, CART_REFERENCE,
                cartId, actor, moves, operation, cartId, cartItemId, reservation.id(), idempotencyKey));
        log.info("reservation created storeId={} cartId={} cartItemId={} reservationId={} items={} revision={}",
                storeId, cartId, cartItemId, reservation.id(), moves.size(), reservation.demandRevision());
        return reservation.id();
    }

    private UUID applyDelta(InventoryReservation reservation, ReservationDemand demand, Instant expiresAt,
            UUID actor, String operation, String idempotencyKey) {
        SortedMap<UUID, BigDecimal> deltas = ReservationDeltaEngine.delta(reservation.demandSnapshot(),
                demand.lines());
        List<StockTransactionLine> reserved = new ArrayList<>();
        List<StockTransactionLine> released = new ArrayList<>();
        UUID storeId = reservation.storeId();
        for (Map.Entry<UUID, BigDecimal> entry : deltas.entrySet()) {
            UUID item = entry.getKey();
            BigDecimal delta = entry.getValue();
            InventoryBalance balance = requireBalance(storeId, item);
            BigDecimal before = balance.reservedStock();
            if (delta.signum() > 0) {
                if (balances.reserve(storeId, item, delta) != 1) {
                    throw insufficientStock(item, delta, balance);
                }
                reserved.add(new StockTransactionLine(item, delta, null, before, before.add(delta)));
                reservation.increase(item, delta, demand.baseUnits().get(item));
            } else {
                BigDecimal quantity = delta.negate();
                if (balances.release(storeId, item, quantity) != 1) {
                    throw new ConflictException("RESERVATION_CONFLICT",
                            "Held quantity changed concurrently for inventory item " + item);
                }
                released.add(new StockTransactionLine(item, delta, null, before, before.subtract(quantity)));
                reservation.decrease(item, quantity);
            }
        }
        boolean demandMoved = !deltas.isEmpty();
        boolean provenanceChanged = !java.util.Objects.equals(reservation.sourceRecipeVersionId(),
                demand.recipeVersionId());
        if (demandMoved || provenanceChanged) {
            reservation.revise(demand.recipeVersionId(), demand.recipeVersionNumber(), demand.resolutionSource(),
                    expiresAt);
        }
        writeLedger(reservation, reserved, StockTransactionType.SALE_RESERVATION, operation, actor, idempotencyKey);
        writeLedger(reservation, released, StockTransactionType.SALE_RELEASE, operation, actor, idempotencyKey);
        log.info("reservation delta applied cartId={} cartItemId={} reservationId={} reserved={} released={} revision={}",
                reservation.cartId(), reservation.cartItemId(), reservation.id(), reserved.size(), released.size(),
                reservation.demandRevision());
        return reservation.id();
    }

    private void releaseHeld(InventoryReservation reservation, String reason, UUID actor) {
        if (reservation.status() != ReservationStatus.ACTIVE) {
            // Already released/expired/consumed: replaying a release must not move stock twice.
            log.debug("reservation release skipped cartItemId={} reservationId={} status={}",
                    reservation.cartItemId(), reservation.id(), reservation.status());
            return;
        }
        UUID storeId = reservation.storeId();
        List<StockTransactionLine> moves = new ArrayList<>();
        for (InventoryReservationLine line : sortedLines(reservation)) {
            UUID item = line.ingredientId();
            BigDecimal quantity = line.quantityReserved();
            InventoryBalance balance = requireBalance(storeId, item);
            BigDecimal before = balance.reservedStock();
            if (balances.release(storeId, item, quantity) != 1) {
                throw new ConflictException("RESERVATION_CONFLICT",
                        "Held quantity changed concurrently for inventory item " + item);
            }
            moves.add(new StockTransactionLine(item, quantity.negate(), null, before, before.subtract(quantity)));
        }
        reservation.released(reason);
        writeLedger(reservation, moves, StockTransactionType.SALE_RELEASE, reason, actor, null);
        log.info("reservation released storeId={} cartId={} cartItemId={} reservationId={} reason={} items={}",
                storeId, reservation.cartId(), reservation.cartItemId(), reservation.id(), reason, moves.size());
    }

    private void writeLedger(InventoryReservation reservation, List<StockTransactionLine> moves,
            StockTransactionType type, String reason, UUID actor, String idempotencyKey) {
        if (moves.isEmpty()) {
            return;
        }
        ledger.save(StockTransaction.create(reservation.storeId(), type, CART_REFERENCE, reservation.cartId(),
                actor, moves, reason, reservation.cartId(), reservation.cartItemId(), reservation.id(),
                idempotencyKey));
    }

    private static List<InventoryReservationLine> sortedLines(InventoryReservation reservation) {
        List<InventoryReservationLine> lines = new ArrayList<>(reservation.lines());
        lines.sort(Comparator.comparing((InventoryReservationLine line) -> line.ingredientId()));
        return lines;
    }

    private InventoryBalance requireBalance(UUID storeId, UUID item) {
        return balances.findById(new InventoryBalanceId(storeId, item))
                .orElseThrow(() -> {
                    log.warn("inventory balance missing storeId={} inventoryItemId={}", storeId, item);
                    return new ConflictException("INVENTORY_BALANCE_NOT_FOUND",
                            "No inventory balance exists for inventory item " + item + " at this store");
                });
    }

    private ConflictException insufficientStock(UUID item, BigDecimal requested, InventoryBalance balance) {
        BigDecimal available = balance.availableStock();
        log.warn("reservation rejected inventoryItemId={} requested={} available={}", item,
                requested.toPlainString(), available.toPlainString());
        return new ConflictException("INSUFFICIENT_STOCK",
                "Insufficient stock for inventory item " + item + ": requested " + requested.toPlainString()
                        + ", available " + available.toPlainString());
    }
}
