package com.smartcoffee.modules.payment.application;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.smartcoffee.modules.inventory.application.InventoryReservationFinalizationService;
import com.smartcoffee.modules.inventory.application.PaidOrderRecoveryService;
import com.smartcoffee.modules.inventory.application.ReservationMetrics;
import com.smartcoffee.modules.inventory.domain.InventoryReservation;
import com.smartcoffee.modules.inventory.persistence.InventoryReconciliationQueries;
import com.smartcoffee.modules.inventory.persistence.InventoryReconciliationQueries.PaymentSettlement;
import com.smartcoffee.modules.inventory.persistence.InventoryReservationRepository;
import com.smartcoffee.modules.inventory.persistence.ReservationExpiryClaimRepository;
import com.smartcoffee.modules.ordering.domain.Order;
import com.smartcoffee.modules.ordering.domain.OrderStatus;
import com.smartcoffee.modules.ordering.persistence.OrderRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Slice 2E order-expiry worker decision tests (spec §4B/§6/§21 A). The worker must
 * NEVER release a settled order's hold (it recovers via consumption instead), must
 * release a cancelled order's hold, must follow the existing expire→release path for
 * a genuinely unpaid order, must leave an ambiguous in-flight payment untouched, and
 * must no-op on a non-ACTIVE or non-order reservation. Only the decision tree is
 * exercised here — the SKIP LOCKED claim/idempotency is covered by the concurrency IT.
 */
class OrderReservationExpiryServiceTest {

    private ReservationExpiryClaimRepository claims;
    private InventoryReservationRepository reservations;
    private OrderRepository orders;
    private InventoryReconciliationQueries queries;
    private PaymentOrderPort orderLifecycle;
    private InventoryReservationFinalizationService finalization;
    private PaidOrderRecoveryService recovery;
    private com.smartcoffee.modules.promotion.application.PromotionRedemptionLifecycleService promotions;
    private OrderReservationExpiryService service;

    private final UUID resvId = UUID.randomUUID();
    private final UUID store = UUID.randomUUID();
    private final UUID order = UUID.randomUUID();
    private final UUID item = UUID.randomUUID();

    @BeforeEach
    void setup() {
        claims = mock(ReservationExpiryClaimRepository.class);
        reservations = mock(InventoryReservationRepository.class);
        orders = mock(OrderRepository.class);
        queries = mock(InventoryReconciliationQueries.class);
        orderLifecycle = mock(PaymentOrderPort.class);
        finalization = mock(InventoryReservationFinalizationService.class);
        recovery = mock(PaidOrderRecoveryService.class);
        promotions = mock(com.smartcoffee.modules.promotion.application.PromotionRedemptionLifecycleService.class);
        ReservationMetrics metrics = mock(ReservationMetrics.class);
        Clock clock = Clock.fixed(Instant.parse("2026-10-27T10:00:00Z"), ZoneOffset.UTC);
        service = new OrderReservationExpiryService(claims, reservations, orders, queries, orderLifecycle, finalization,
                recovery, mock(com.smartcoffee.infrastructure.retry.TransientRetryExecutor.class), metrics, clock,
                promotions, 50, 5, Duration.ofMinutes(5));
    }

    private InventoryReservation activeOrderHold() {
        return InventoryReservation.fromCartHolds(store, order, UUID.randomUUID(), Instant.now().plusSeconds(600),
                Map.of(item, BigDecimal.TEN), Map.of(), "key");
    }

    private void activeHolds(InventoryReservation r) {
        when(reservations.findById(resvId)).thenReturn(Optional.of(r));
        when(reservations.lock(resvId)).thenReturn(Optional.of(r));
    }

    private void stubOrderLock(OrderStatus status) {
        Order o = mock(Order.class);
        when(o.status()).thenReturn(status);
        when(orders.lock(order)).thenReturn(Optional.of(o));
    }

    @Test
    void settledOrderRecoversByConsumptionAndNeverReleases() {
        InventoryReservation held = activeOrderHold();
        activeHolds(held);
        stubOrderLock(OrderStatus.CONFIRMED);
        when(queries.paymentSettlement(order)).thenReturn(PaymentSettlement.SETTLED);
        when(recovery.recover(eq(order), isNull())).thenReturn(PaidOrderRecoveryService.Decision.CONSUMED);

        service.processOne(resvId);

        verify(recovery).recover(eq(order), isNull());
        verify(finalization, never()).releaseOrder(any(), any(), any());
        verify(orderLifecycle, never()).expire(any());
    }

    @Test
    void cancelledOrderReleasesItsHold() {
        InventoryReservation held = activeOrderHold();
        activeHolds(held);
        stubOrderLock(OrderStatus.CANCELLED);
        when(queries.paymentSettlement(order)).thenReturn(PaymentSettlement.NOT_SETTLED);
        when(finalization.releaseOrder(eq(order), any(), isNull())).thenReturn(true);

        service.processOne(resvId);

        verify(finalization).releaseOrder(eq(order), any(), isNull());
        verify(promotions).expireForOrder(order, "ORDER_RESERVATION_EXPIRED", null);
        verify(recovery, never()).recover(any(), any());
    }

    @Test
    void awaitingPaymentUnpaidOrderExpiresThenReleases() {
        InventoryReservation held = activeOrderHold();
        activeHolds(held);
        stubOrderLock(OrderStatus.AWAITING_PAYMENT);
        when(queries.paymentSettlement(order)).thenReturn(PaymentSettlement.NOT_SETTLED);
        when(finalization.releaseOrder(eq(order), any(), isNull())).thenReturn(true);

        service.processOne(resvId);

        var inOrder = inOrder(orderLifecycle, finalization, promotions);
        inOrder.verify(orderLifecycle).expire(order);
        inOrder.verify(finalization).releaseOrder(eq(order), any(), isNull());
        inOrder.verify(promotions).expireForOrder(order, "ORDER_RESERVATION_EXPIRED", null);
    }

    @Test
    void ambiguousInFlightPaymentIsLeftUntouched() {
        InventoryReservation held = activeOrderHold();
        activeHolds(held);
        stubOrderLock(OrderStatus.AWAITING_PAYMENT);
        when(queries.paymentSettlement(order)).thenReturn(PaymentSettlement.AMBIGUOUS);

        service.processOne(resvId);

        verify(orderLifecycle, never()).expire(any());
        verify(finalization, never()).releaseOrder(any(), any(), any());
        verify(promotions, never()).expireForOrder(any(), any(), any());
        verify(recovery, never()).recover(any(), any());
    }

    @Test
    void terminalReservationIsANoOpBeforeAnyLocking() {
        InventoryReservation consumed = activeOrderHold();
        consumed.consumed(UUID.randomUUID());
        when(reservations.findById(resvId)).thenReturn(Optional.of(consumed));

        service.processOne(resvId);

        verify(orders, never()).lock(any());
    }

    @Test
    void missingOrNonOrderReservationIsANoOp() {
        when(reservations.findById(resvId)).thenReturn(Optional.empty());
        service.processOne(resvId);

        InventoryReservation cartHold = InventoryReservation.forCart(store, UUID.randomUUID(), item,
                Instant.now().plusSeconds(600), Map.of(item, BigDecimal.TEN), Map.of(item, "G"), null, null, "VARIANT",
                "key");
        when(reservations.findById(resvId)).thenReturn(Optional.of(cartHold));
        service.processOne(resvId);

        verify(orders, never()).lock(any());
    }

    @Test
    void goneOrderSkipsBeforeLockingTheReservation() {
        InventoryReservation held = activeOrderHold();
        when(reservations.findById(resvId)).thenReturn(Optional.of(held));
        when(orders.lock(order)).thenReturn(Optional.empty());

        service.processOne(resvId);

        verify(reservations, never()).lock(resvId);
    }

    @Test
    void reservationDemotedAfterOrderLockIsNotActedOn() {
        InventoryReservation held = activeOrderHold();
        when(reservations.findById(resvId)).thenReturn(Optional.of(held));
        stubOrderLock(OrderStatus.CANCELLED);
        InventoryReservation released = activeOrderHold();
        released.released("ORDER_CANCELLED");
        when(reservations.lock(resvId)).thenReturn(Optional.of(released));

        service.processOne(resvId);

        verify(queries, never()).paymentSettlement(any());
        verify(finalization, never()).releaseOrder(any(), any(), any());
    }
}
