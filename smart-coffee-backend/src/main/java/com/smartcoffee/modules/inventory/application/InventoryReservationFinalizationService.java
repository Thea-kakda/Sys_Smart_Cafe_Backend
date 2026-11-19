package com.smartcoffee.modules.inventory.application;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.modules.inventory.domain.InventoryBalance;
import com.smartcoffee.modules.inventory.domain.InventoryBalanceId;
import com.smartcoffee.modules.inventory.domain.InventoryReservation;
import com.smartcoffee.modules.inventory.domain.InventoryReservationLine;
import com.smartcoffee.modules.inventory.domain.ReservationStatus;
import com.smartcoffee.modules.inventory.domain.StockTransaction;
import com.smartcoffee.modules.inventory.domain.StockTransactionLine;
import com.smartcoffee.modules.inventory.domain.StockTransactionType;
import com.smartcoffee.modules.inventory.persistence.IngredientRepository;
import com.smartcoffee.modules.inventory.persistence.InventoryBalanceRepository;
import com.smartcoffee.modules.inventory.persistence.InventoryReservationRepository;
import com.smartcoffee.modules.inventory.persistence.StockTransactionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reservation finalization (Slice 2D): the one place where a hold stops being a
 * hold.
 *
 * <p>Three terminal paths, all of them single-shot:
 * <ul>
 *   <li><b>convert</b> — checkout hands the persisted cart snapshot to the order.
 *       Ownership moves, the demand does not: {@code reserved_stock} and
 *       {@code current_stock} are untouched, no {@code SALE_RELEASE} and no
 *       second {@code SALE_RESERVATION} is written, so there is no committed
 *       state in which the stock is briefly available again.</li>
 *   <li><b>consume</b> — the order is settled. The exact reservation snapshot is
 *       drawn down ({@code current_stock} and {@code reserved_stock} fall by the
 *       same quantity) and exactly one {@code SALE_CONSUMPTION} row is written.</li>
 *   <li><b>release</b> — the order is cancelled or its payment window expires.
 *       Only {@code reserved_stock} moves back; exactly one
 *       {@code SALE_RELEASE} row is written.</li>
 * </ul>
 *
 * <p>Invariants: balance rows are visited in ascending inventory-item id order,
 * every terminal transition happens under a pessimistic write lock on the
 * reservation row, and a replay of any of the three is a silent no-op (a
 * reservation only leaves {@code ACTIVE} once). Consumption additionally carries
 * a database backstop ({@code uk_stock_consumption_reservation}).
 *
 * <p>Demand is never recalculated here: it comes from the persisted reservation
 * lines, so a later recipe, variant or option edit cannot change what is consumed
 * or released.
 */
@Service
public class InventoryReservationFinalizationService {

    private static final Logger log = LoggerFactory.getLogger(InventoryReservationFinalizationService.class);
    private static final String ORDER_REFERENCE = "ORDER";
    private static final String CART_REFERENCE = "CART";

    private final InventoryBalanceRepository balances;
    private final InventoryReservationRepository reservations;
    private final StockTransactionRepository ledger;
    private final IngredientRepository ingredients;
    private final FefoConsumptionService fefo;

    public InventoryReservationFinalizationService(InventoryBalanceRepository balances,
            InventoryReservationRepository reservations, StockTransactionRepository ledger,
            IngredientRepository ingredients, FefoConsumptionService fefo) {
        this.balances = balances;
        this.reservations = reservations;
        this.ledger = ledger;
        this.ingredients = ingredients;
        this.fefo = fefo;
    }

    /**
     * Read-only view of one cart line's persisted hold, used by checkout to build
     * the order snapshot without recalculating anything.
     */
    public record CartHold(UUID reservationId, UUID cartItemId, ReservationStatus status, long demandRevision,
            UUID recipeVersionId, Integer recipeVersionNumber, String resolutionSource,
            Map<UUID, BigDecimal> demand, Map<UUID, String> baseUnits) {
    }

    /** A cart line that never held stock (pre-V045) and must be covered now. */
    public record LegacyLine(UUID cartItemId, UUID orderItemId, ReservationDemand demand) {
    }

    /** Every hold of one cart, keyed by cart line id. */
    @Transactional(readOnly = true)
    public Map<UUID, CartHold> holdsForCart(UUID cartId) {
        Map<UUID, CartHold> holds = new LinkedHashMap<>();
        for (InventoryReservation r : reservations.findActiveByCartId(cartId)) {
            Map<UUID, String> units = new HashMap<>();
            for (InventoryReservationLine line : r.lines()) {
                if (line.baseUnitCode() != null) {
                    units.put(line.ingredientId(), line.baseUnitCode());
                }
            }
            holds.put(r.cartItemId(), new CartHold(r.id(), r.cartItemId(), r.status(), r.demandRevision(),
                    r.sourceRecipeVersionId(), r.sourceRecipeVersionNumber(), r.resolutionSource(),
                    r.demandSnapshot(), units));
        }
        return holds;
    }

    /**
     * Converts the cart's ACTIVE holds into the order's single reservation.
     *
     * <p>The order reservation is created from the persisted cart snapshots
     * (plus, only for legacy lines, the freshly calculated demand). The held part
     * is a metadata-only transfer — no balance moves — while the legacy part is
     * reserved for real in the same transaction.
     *
     * @param orderLineByCartItem cart line id → the order line that snapshotted it
     * @param legacy              cart lines that had no hold; may be empty
     * @return the order reservation id, or empty when the order holds nothing
     */
    @Transactional
    public Optional<UUID> convert(UUID storeId, UUID cartId, UUID orderId, Instant expiresAt,
            Map<UUID, UUID> orderLineByCartItem, List<LegacyLine> legacy, UUID actor, String idempotencyKey) {
        if (storeId == null || cartId == null || orderId == null) {
            throw new ConflictException("RESERVATION_CONFLICT", "Store, cart and order are required for conversion");
        }
        if (reservations.findByOrderId(orderId).isPresent()) {
            // A second order reservation for one order would double count demand;
            // the unique order_id index would refuse it anyway.
            throw new ConflictException("RESERVATION_ALREADY_CONVERTED",
                    "This order already owns an inventory reservation");
        }
        List<InventoryReservation> holds = reservations.lockActiveByCartId(cartId);
        Map<UUID, BigDecimal> held = new TreeMap<>();
        Map<UUID, String> units = new HashMap<>();
        for (InventoryReservation hold : holds) {
            if (!hold.storeId().equals(storeId)) {
                throw new ConflictException("CART_RESERVATION_CONFLICT", "Cart hold belongs to another store");
            }
            if (hold.cartItemId() == null || !orderLineByCartItem.containsKey(hold.cartItemId())) {
                throw new ConflictException("CART_RESERVATION_CONFLICT",
                        "Cart hold " + hold.id() + " does not belong to a cart line being checked out");
            }
            for (InventoryReservationLine line : hold.lines()) {
                held.merge(line.ingredientId(), line.quantityReserved(), (left, right) -> left.add(right));
                if (line.baseUnitCode() != null) {
                    units.putIfAbsent(line.ingredientId(), line.baseUnitCode());
                }
            }
        }

        Map<UUID, BigDecimal> missing = new TreeMap<>();
        for (LegacyLine line : legacy == null ? List.<LegacyLine>of() : legacy) {
            if (line.demand() == null || line.demand().isEmpty()) {
                continue;
            }
            for (Map.Entry<UUID, BigDecimal> entry : line.demand().lines().entrySet()) {
                missing.merge(entry.getKey(), entry.getValue(), (left, right) -> left.add(right));
                if (line.demand().baseUnits().get(entry.getKey()) != null) {
                    units.putIfAbsent(entry.getKey(), line.demand().baseUnits().get(entry.getKey()));
                }
            }
        }

        Map<UUID, BigDecimal> total = new TreeMap<>(held);
        missing.forEach((item, quantity) -> total.merge(item, quantity, (left, right) -> left.add(right)));
        if (total.isEmpty()) {
            log.info("conversion skipped (nothing held) cartId={} orderId={}", cartId, orderId);
            return Optional.empty();
        }

        InventoryReservation orderReservation = InventoryReservation.fromCartHolds(storeId, orderId, cartId,
                expiresAt, total, units, idempotencyKey);
        reservations.saveAndFlush(orderReservation);
        for (InventoryReservation hold : holds) {
            hold.converted(orderId, orderLineByCartItem.get(hold.cartItemId()), orderReservation.id(), expiresAt);
        }

        // Only the part no cart line held is reserved here; the converted part is
        // already reserved and must not move again.
        List<StockTransactionLine> moves = new ArrayList<>();
        for (Map.Entry<UUID, BigDecimal> entry : missing.entrySet()) {
            UUID item = entry.getKey();
            BigDecimal quantity = entry.getValue();
            InventoryBalance balance = requireBalance(storeId, item);
            BigDecimal before = balance.reservedStock();
            if (balances.reserve(storeId, item, quantity) != 1) {
                throw insufficientStock(item, quantity, balance);
            }
            moves.add(new StockTransactionLine(item, quantity, null, before, before.add(quantity)));
        }
        if (!moves.isEmpty()) {
            ledger.save(StockTransaction.create(storeId, StockTransactionType.SALE_RESERVATION, CART_REFERENCE,
                    cartId, actor, moves, ReservationReasons.LEGACY_CHECKOUT_RESERVE, cartId, null,
                    orderReservation.id(), idempotencyKey));
        }
        log.info("conversion committed storeId={} cartId={} orderId={} reservationId={} cartHolds={} lines={} "
                        + "newlyReserved={}", storeId, cartId, orderId, orderReservation.id(), holds.size(),
                orderReservation.lines().size(), moves.size());
        return Optional.of(orderReservation.id());
    }

    /**
     * Consumes the order's ACTIVE reservation at settlement.
     *
     * @return true when this call drew the stock down, false when the order owns
     *         nothing (not tracked) or was already settled
     */
    @Transactional
    public boolean consumeOrder(UUID orderId, UUID actor) {
        Optional<InventoryReservation> found = reservations.lockByOrderId(orderId);
        if (found.isEmpty()) {
            log.debug("consumption skipped, order owns no reservation orderId={}", orderId);
            return false;
        }
        InventoryReservation r = found.get();
        if (r.status() == ReservationStatus.CONSUMED) {
            log.debug("consumption replay ignored orderId={} reservationId={}", orderId, r.id());
            return false;
        }
        if (r.status() != ReservationStatus.ACTIVE) {
            throw new ConflictException("RESERVATION_NOT_ACTIVE",
                    "Reservation is " + r.status().name() + " and cannot be consumed");
        }
        List<StockTransactionLine> entries = new ArrayList<>();
        for (InventoryReservationLine line : sortedLines(r)) {
            UUID item = line.ingredientId();
            BigDecimal quantity = line.quantityReserved();
            BigDecimal before = requireBalance(r.storeId(), item).currentStock();
            if (balances.consume(r.storeId(), item, quantity) != 1) {
                throw new ConflictException("RESERVATION_BALANCE_CONFLICT",
                        "Reserved quantity for inventory item " + item + " is no longer available");
            }
            line.consume();
            // M3: a batch-tracked ingredient draws its FEFO lots; the aggregate
            // guard above stays the authority. Untracked items keep one line.
            boolean tracked = ingredients.findById(item).map(ingredient -> ingredient.trackBatches()).orElse(false);
            if (tracked) {
                entries.addAll(fefo.drawLines(r.storeId(), item, quantity, before));
            } else {
                entries.add(new StockTransactionLine(item, quantity.negate(), null, before,
                        before.subtract(quantity)));
            }
        }
        r.consumed(actor);
        if (!entries.isEmpty()) {
            ledger.save(StockTransaction.create(r.storeId(), StockTransactionType.SALE_CONSUMPTION, ORDER_REFERENCE,
                    orderId, actor, entries, ReservationReasons.ORDER_SETTLED, r.cartId(), r.cartItemId(), r.id(),
                    consumptionKey(r.id())));
        }
        log.info("reservation consumed storeId={} orderId={} reservationId={} lines={}", r.storeId(), orderId, r.id(),
                entries.size());
        return true;
    }

    /**
     * Releases the order's ACTIVE reservation (cancellation before consumption, or
     * an expired payment window). Only {@code reserved_stock} moves.
     *
     * @return true when this call gave the stock back; false when nothing was held
     *         or the reservation had already reached a terminal state
     */
    @Transactional
    public boolean releaseOrder(UUID orderId, String reason, UUID actor) {
        Optional<InventoryReservation> found = reservations.lockByOrderId(orderId);
        if (found.isEmpty()) {
            return false;
        }
        InventoryReservation r = found.get();
        if (r.status() != ReservationStatus.ACTIVE) {
            // Consumed stock can never be released, and a replay must not move it twice.
            log.debug("release ignored orderId={} reservationId={} status={}", orderId, r.id(), r.status());
            return false;
        }
        List<StockTransactionLine> moves = new ArrayList<>();
        for (InventoryReservationLine line : sortedLines(r)) {
            UUID item = line.ingredientId();
            BigDecimal quantity = line.quantityReserved();
            BigDecimal before = requireBalance(r.storeId(), item).reservedStock();
            if (balances.release(r.storeId(), item, quantity) != 1) {
                throw new ConflictException("RESERVATION_BALANCE_CONFLICT",
                        "Held quantity for inventory item " + item + " changed concurrently");
            }
            moves.add(new StockTransactionLine(item, quantity.negate(), null, before, before.subtract(quantity)));
        }
        r.released(reason);
        if (!moves.isEmpty()) {
            ledger.save(StockTransaction.create(r.storeId(), StockTransactionType.SALE_RELEASE, ORDER_REFERENCE,
                    orderId, actor, moves, reason, r.cartId(), r.cartItemId(), r.id(), null));
        }
        log.info("reservation released storeId={} orderId={} reservationId={} reason={} lines={}", r.storeId(),
                orderId, r.id(), reason, moves.size());
        return true;
    }

    /**
     * Releases ONE reservation addressed by id (Slice 2E, spec §12 — the only
     * release primitive that is not order- or cart-scoped). Used by the
     * administrative release endpoint so a lone abandoned hold can be given back
     * without touching a whole cart or an order that may already be terminal.
     *
     * <p>Identical guards to {@link #releaseOrder}: the reservation is locked for
     * write, only an {@code ACTIVE} hold moves, {@code current_stock} is untouched,
     * {@code reserved_stock} falls by the exact persisted snapshot, and exactly one
     * {@code SALE_RELEASE} is written (referencing the order when present, otherwise
     * the cart). A replay on a terminal reservation is a silent {@code false}.
     *
     * @return true when this call gave the stock back; false when nothing was held
     */
    @Transactional
    public boolean releaseReservation(UUID reservationId, String reason, UUID actor) {
        Optional<InventoryReservation> found = reservations.lock(reservationId);
        if (found.isEmpty()) {
            return false;
        }
        InventoryReservation r = found.get();
        if (r.status() != ReservationStatus.ACTIVE) {
            log.debug("admin release ignored reservationId={} status={}", reservationId, r.status());
            return false;
        }
        List<StockTransactionLine> moves = new ArrayList<>();
        for (InventoryReservationLine line : sortedLines(r)) {
            UUID item = line.ingredientId();
            BigDecimal quantity = line.quantityReserved();
            BigDecimal before = requireBalance(r.storeId(), item).reservedStock();
            if (balances.release(r.storeId(), item, quantity) != 1) {
                throw new ConflictException("RESERVATION_BALANCE_CONFLICT",
                        "Held quantity for inventory item " + item + " changed concurrently");
            }
            moves.add(new StockTransactionLine(item, quantity.negate(), null, before, before.subtract(quantity)));
        }
        r.released(reason);
        if (!moves.isEmpty()) {
            String referenceType = r.orderId() != null ? ORDER_REFERENCE : CART_REFERENCE;
            UUID referenceId = r.orderId() != null ? r.orderId() : r.cartId();
            ledger.save(StockTransaction.create(r.storeId(), StockTransactionType.SALE_RELEASE, referenceType,
                    referenceId, actor, moves, reason, r.cartId(), r.cartItemId(), r.id(), null));
        }
        log.info("reservation released by id storeId={} reservationId={} reason={} lines={}", r.storeId(),
                reservationId, reason, moves.size());
        return true;
    }

    // ------------------------------------------------------------- internals

    /**
     * Stable idempotency identity of one consumption: the reservation can only be
     * consumed once, so its id names the ledger row that did it.
     */
    private static String consumptionKey(UUID reservationId) {
        return "CONSUME-" + reservationId;
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
                    return new ResourceNotFoundException("INVENTORY_BALANCE_NOT_FOUND",
                            "No inventory balance exists for inventory item " + item);
                });
    }

    private ConflictException insufficientStock(UUID item, BigDecimal requested, InventoryBalance balance) {
        BigDecimal available = balance.availableStock();
        log.warn("legacy checkout reservation rejected inventoryItemId={} requested={} available={}", item,
                requested.toPlainString(), available.toPlainString());
        return new ConflictException("INSUFFICIENT_STOCK",
                "Insufficient stock for inventory item " + item + ": requested " + requested.toPlainString()
                        + ", available " + available.toPlainString());
    }
}
