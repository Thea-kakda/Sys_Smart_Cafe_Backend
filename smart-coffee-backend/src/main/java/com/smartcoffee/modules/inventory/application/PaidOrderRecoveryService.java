package com.smartcoffee.modules.inventory.application;

import com.smartcoffee.modules.inventory.domain.InventoryBalance;
import com.smartcoffee.modules.inventory.domain.InventoryReservation;
import com.smartcoffee.modules.inventory.domain.InventoryReservationLine;
import com.smartcoffee.modules.inventory.domain.ReservationStatus;
import com.smartcoffee.modules.inventory.domain.StockTransactionType;
import com.smartcoffee.modules.inventory.persistence.InventoryBalanceRepository;
import com.smartcoffee.modules.inventory.persistence.InventoryReconciliationQueries;
import com.smartcoffee.modules.inventory.persistence.InventoryReconciliationQueries.OrderSnapshot;
import com.smartcoffee.modules.inventory.persistence.InventoryReconciliationQueries.PaymentSettlement;
import com.smartcoffee.modules.inventory.persistence.InventoryReservationRepository;
import com.smartcoffee.modules.inventory.persistence.StockTransactionRepository;
import java.math.BigDecimal;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Safe recovery of the paid-but-unconsumed crash window (Slice 2E, spec §9).
 *
 * <p>Settlement, the order update and consumption already share one transaction
 * (see {@code JpaPaymentOrderAdapter#complete}), so this state is impossible for
 * an internally settled order. It can still appear from an external confirmation
 * or legacy data, and this service is the only place allowed to close it.
 *
 * <p>Automatic consumption happens ONLY when all nine §9 conditions hold. If any
 * is ambiguous the reservation is left exactly as it is and the caller reports a
 * CRITICAL finding for manual review — the code never consumes on a guess. When
 * it does act it calls the SAME {@link InventoryReservationFinalizationService#consumeOrder}
 * used by normal settlement, so there is never a second consumption algorithm.
 */
@Service
public class PaidOrderRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(PaidOrderRecoveryService.class);
    /** Order statuses that are compatible with a successfully-settled payment (§9). */
    private static final Set<String> SETTLEMENT_COMPATIBLE_ORDER_STATUS = Set.of("CONFIRMED", "PREPARING", "READY",
            "COMPLETED");

    private final InventoryReservationRepository reservations;
    private final StockTransactionRepository ledger;
    private final InventoryBalanceRepository balances;
    private final InventoryReconciliationQueries queries;
    private final InventoryReservationFinalizationService finalization;

    public PaidOrderRecoveryService(InventoryReservationRepository reservations, StockTransactionRepository ledger,
            InventoryBalanceRepository balances, InventoryReconciliationQueries queries,
            InventoryReservationFinalizationService finalization) {
        this.reservations = reservations;
        this.ledger = ledger;
        this.balances = balances;
        this.queries = queries;
        this.finalization = finalization;
    }

    /** Outcome of a recovery attempt. Only {@link Decision#CONSUMED} moved stock. */
    public enum Decision {
        /** all nine conditions held; consumption applied exactly once */
        CONSUMED,
        /** nothing to do: the reservation was already CONSUMED (idempotent replay) */
        ALREADY_CONSUMED,
        /** at least one condition was unmet/ambiguous; no stock changed, manual review */
        MANUAL_REVIEW
    }

    /**
     * Evaluates the §9 gate for the order's ACTIVE reservation and, only if every
     * condition provably holds, consumes it through the shared finalization path.
     */
    @Transactional
    public Decision recover(UUID orderId, UUID actor) {
        Optional<InventoryReservation> found = reservations.lockByOrderId(orderId);
        if (found.isEmpty()) {
            log.warn("recovery declined: order owns no reservation orderId={}", orderId);
            return Decision.MANUAL_REVIEW;
        }
        InventoryReservation r = found.get();
        if (r.status() == ReservationStatus.CONSUMED) {
            return Decision.ALREADY_CONSUMED;
        }
        if (r.status() != ReservationStatus.ACTIVE) {
            log.warn("recovery declined: reservation not ACTIVE orderId={} status={}", orderId, r.status());
            return Decision.MANUAL_REVIEW;
        }

        // 4. reservation belongs to the same order and store
        OrderSnapshot order = queries.orderSnapshot(orderId);
        if (order == null || !order.storeId().equals(r.storeId())) {
            log.warn("recovery declined: order/store unresolvable or mismatched orderId={} reservationId={}", orderId,
                    r.id());
            return Decision.MANUAL_REVIEW;
        }
        // 1. payment is definitively PAID/SETTLED (not PROCESSING/PENDING-ambiguous)
        if (queries.paymentSettlement(orderId) != PaymentSettlement.SETTLED) {
            log.warn("recovery declined: settlement not provably committed orderId={}", orderId);
            return Decision.MANUAL_REVIEW;
        }
        // 2. order status is compatible with a successful payment
        if (!SETTLEMENT_COMPATIBLE_ORDER_STATUS.contains(order.status())) {
            log.warn("recovery declined: order status incompatible orderId={} status={}", orderId, order.status());
            return Decision.MANUAL_REVIEW;
        }
        // 5. one SALE_CONSUMPTION does not already exist
        if (ledger.existsByReservationIdAndType(r.id(), StockTransactionType.SALE_CONSUMPTION)) {
            log.warn("recovery declined: consumption ledger already present orderId={} reservationId={}", orderId,
                    r.id());
            return Decision.MANUAL_REVIEW;
        }
        // 8. no SALE_RELEASE exists for the same reservation (never consume a release)
        if (ledger.existsByReservationIdAndType(r.id(), StockTransactionType.SALE_RELEASE)) {
            log.warn("recovery declined: release ledger conflicts with consumption orderId={} reservationId={}",
                    orderId, r.id());
            return Decision.MANUAL_REVIEW;
        }
        // 6/7. reserved and current stock still satisfy a guarded consumption
        for (InventoryReservationLine line : r.lines()) {
            Optional<InventoryBalance> balance = balances.find(r.storeId(), line.ingredientId());
            if (balance.isEmpty()) {
                log.warn("recovery declined: no balance row storeId={} item={}", r.storeId(), line.ingredientId());
                return Decision.MANUAL_REVIEW;
            }
            BigDecimal held = line.quantityReserved();
            if (balance.get().reservedStock().compareTo(held) < 0
                    || balance.get().currentStock().compareTo(held) < 0) {
                log.warn("recovery declined: guarded consumption not satisfiable reservationId={} item={}", r.id(),
                        line.ingredientId());
                return Decision.MANUAL_REVIEW;
            }
        }

        // All nine conditions hold. Reuse the settlement consumption path exactly.
        boolean consumed = finalization.consumeOrder(orderId, actor);
        log.info("paid-but-unconsumed recovered orderId={} reservationId={} consumed={}", orderId, r.id(), consumed);
        return consumed ? Decision.CONSUMED : Decision.ALREADY_CONSUMED;
    }
}
