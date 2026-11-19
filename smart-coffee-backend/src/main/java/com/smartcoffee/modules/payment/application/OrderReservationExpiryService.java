package com.smartcoffee.modules.payment.application;

import com.smartcoffee.infrastructure.retry.TransientRetryExecutor;
import com.smartcoffee.modules.inventory.application.InventoryReservationFinalizationService;
import com.smartcoffee.modules.inventory.application.PaidOrderRecoveryService;
import com.smartcoffee.modules.inventory.application.ReservationMetrics;
import com.smartcoffee.modules.inventory.application.ReservationReasons;
import com.smartcoffee.modules.inventory.domain.InventoryReservation;
import com.smartcoffee.modules.inventory.domain.ReservationStatus;
import com.smartcoffee.modules.inventory.persistence.InventoryReconciliationQueries;
import com.smartcoffee.modules.inventory.persistence.InventoryReconciliationQueries.PaymentSettlement;
import com.smartcoffee.modules.inventory.persistence.InventoryReservationRepository;
import com.smartcoffee.modules.inventory.persistence.ReservationExpiryClaimRepository;
import com.smartcoffee.modules.ordering.domain.Order;
import com.smartcoffee.modules.ordering.domain.OrderStatus;
import com.smartcoffee.modules.ordering.persistence.OrderRepository;
import com.smartcoffee.modules.promotion.application.PromotionRedemptionLifecycleService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * Order reservation expiry worker (Slice 2E, spec §6). Integrated with — not a
 * replacement for — the payment expiration policy: it never invents a second
 * Order state machine. When an order-owned reservation is past its TTL it
 * reloads the authoritative Order/payment state and then acts through the SAME
 * primitives the rest of the system uses:
 * <ul>
 *   <li>settled payment → {@link PaidOrderRecoveryService} (reuses settlement
 *       consumption) — release is never taken on a paid order;</li>
 *   <li>cancelled order → {@link InventoryReservationFinalizationService#releaseOrder};</li>
 *   <li>still-awaiting order → the existing {@link PaymentOrderPort#expire}
 *       transition then release;</li>
 *   <li>ambiguous state → no stock change, left for reconciliation to report.</li>
 * </ul>
 *
 * <p>Multi-node safe: candidates are claimed with {@code FOR UPDATE SKIP LOCKED}
 * via {@link ReservationExpiryClaimRepository} (bounded batch, stale-claim
 * reclaim, attempt ceiling); each reservation is then processed in its OWN
 * transaction through {@link TransientRetryExecutor}. No transaction is held
 * across the batch, and one node never blocks another's batch.
 */
@Service
public class OrderReservationExpiryService {

    private static final Logger log = LoggerFactory.getLogger(OrderReservationExpiryService.class);

    private final ReservationExpiryClaimRepository claims;
    private final InventoryReservationRepository reservations;
    private final OrderRepository orders;
    private final InventoryReconciliationQueries queries;
    private final PaymentOrderPort orderLifecycle;
    private final InventoryReservationFinalizationService finalization;
    private final PaidOrderRecoveryService recovery;
    private final PromotionRedemptionLifecycleService promotions;
    private final TransientRetryExecutor retry;
    private final ReservationMetrics metrics;
    private final Clock clock;

    private final int batchSize;
    private final int maxAttempts;
    private final Duration staleClaim;
    /** Stable per-instance owner id, mirroring ConfirmedOutboxPublisher. */
    private final String owner = UUID.randomUUID().toString();

    public OrderReservationExpiryService(ReservationExpiryClaimRepository claims,
            InventoryReservationRepository reservations, OrderRepository orders, InventoryReconciliationQueries queries,
            PaymentOrderPort orderLifecycle, InventoryReservationFinalizationService finalization,
            PaidOrderRecoveryService recovery, TransientRetryExecutor retry, ReservationMetrics metrics, Clock clock,
            PromotionRedemptionLifecycleService promotions,
            @Value("${app.inventory.expiry-batch-size:50}") int batchSize,
            @Value("${app.inventory.expiry-max-attempts:5}") int maxAttempts,
            @Value("${app.inventory.expiry-stale-claim:PT5M}") Duration staleClaim) {
        this.claims = claims;
        this.reservations = reservations;
        this.orders = orders;
        this.queries = queries;
        this.orderLifecycle = orderLifecycle;
        this.finalization = finalization;
        this.recovery = recovery;
        this.promotions = promotions;
        this.retry = retry;
        this.metrics = metrics;
        this.clock = clock;
        this.batchSize = batchSize;
        this.maxAttempts = maxAttempts;
        this.staleClaim = staleClaim;
    }

    @Scheduled(fixedDelayString = "${app.inventory.expiration-poll-ms:60000}")
    public void run() {
        metrics.time(ReservationMetrics.EXPIRY_DURATION, () -> {
            Instant now = clock.instant();
            String token = UUID.randomUUID().toString();
            List<UUID> claimed = claims.claimExpiredOrder(owner, token, now, now.minus(staleClaim), batchSize,
                    maxAttempts);
            for (UUID reservationId : claimed) {
                metrics.increment(ReservationMetrics.EXPIRED_PROCESSED, "sourceType", "ORDER");
                try {
                    retry.execute("reservation_expiry", () -> {
                        processOne(reservationId);
                        return null;
                    });
                    claims.markProcessed(reservationId, token);
                } catch (RuntimeException ex) {
                    metrics.increment(ReservationMetrics.EXPIRED_FAILED, "sourceType", "ORDER");
                    log.warn("order reservation expiry failed reservationId={} error={}", reservationId, ex.toString());
                    claims.markError(reservationId, token, ex.getMessage());
                }
            }
            return null;
        });
    }

    /** Runs one candidate's decision. Must execute inside its own transaction. */
    void processOne(UUID reservationId) {
        InventoryReservation r = reservations.findById(reservationId).orElse(null);
        if (r == null || r.status() != ReservationStatus.ACTIVE || r.orderId() == null) {
            return; // already terminal, missing, or not order-owned — no-op
        }
        UUID orderId = r.orderId();
        // Lock Order first, reservation second (matches settlement lock order).
        Order order = orders.lock(orderId).orElse(null);
        if (order == null) {
            log.warn("order reservation expiry: order unresolvable, leaving for reconciliation orderId={} reservationId={}",
                    orderId, reservationId);
            return;
        }
        // Re-read the reservation under the order lock (reload after locking).
        InventoryReservation held = reservations.lock(reservationId).orElse(null);
        if (held == null || held.status() != ReservationStatus.ACTIVE) {
            return;
        }
        PaymentSettlement settlement = queries.paymentSettlement(orderId);
        OrderStatus status = order.status();

        if (settlement == PaymentSettlement.SETTLED) {
            // Paid (or settled-but-unconsumed): never release; finalize if missing.
            PaidOrderRecoveryService.Decision decision = recovery.recover(orderId, null);
            if (decision == PaidOrderRecoveryService.Decision.CONSUMED) {
                metrics.increment(ReservationMetrics.PAID_UNCONSUMED_DETECTED, "outcome", "recovered");
            }
            return;
        }
        if (status == OrderStatus.CANCELLED) {
            // Already cancelled: give the held stock back exactly once.
            if (finalization.releaseOrder(orderId, ReservationReasons.EXPIRED_RESERVATION, null)) {
                metrics.increment(ReservationMetrics.EXPIRED_RELEASED, "sourceType", "ORDER");
            }
            // A promotion still RESERVED on a cancelled order follows the same
            // authoritative expiry (Phase B2): usage is given back exactly once.
            promotions.expireForOrder(orderId, "ORDER_RESERVATION_EXPIRED", null);
            return;
        }
        if (status == OrderStatus.AWAITING_PAYMENT) {
            if (settlement == PaymentSettlement.AMBIGUOUS) {
                log.info("order reservation expiry skipped: payment in flight orderId={}", orderId);
                return; // ambiguous — do not touch stock
            }
            // Unpaid, nothing in flight: follow the existing expiration transition.
            // We already hold the Order row lock, so paymentExpired()'s precondition
            // (AWAITING_PAYMENT + PENDING) cannot race away underneath us here.
            orderLifecycle.expire(orderId);
            if (finalization.releaseOrder(orderId, ReservationReasons.ORDER_RESERVATION_EXPIRED, null)) {
                metrics.increment(ReservationMetrics.EXPIRED_RELEASED, "sourceType", "ORDER");
            }
            promotions.expireForOrder(orderId, "ORDER_RESERVATION_EXPIRED", null);
            return;
        }
        // CONFIRMED/PREPARING/READY/COMPLETED without a settled payment, or any other
        // combination, is genuinely ambiguous: report only, never auto-change stock.
        log.warn("order reservation expiry: ambiguous order state, leaving for reconciliation orderId={} status={} "
                + "reservationId={}", orderId, status, reservationId);
    }
}
