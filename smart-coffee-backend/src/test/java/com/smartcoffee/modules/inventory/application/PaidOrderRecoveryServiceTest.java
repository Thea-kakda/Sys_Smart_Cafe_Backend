package com.smartcoffee.modules.inventory.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.smartcoffee.modules.inventory.domain.InventoryBalance;
import com.smartcoffee.modules.inventory.domain.InventoryReservation;
import com.smartcoffee.modules.inventory.domain.StockTransactionType;
import com.smartcoffee.modules.inventory.persistence.InventoryBalanceRepository;
import com.smartcoffee.modules.inventory.persistence.InventoryReconciliationQueries;
import com.smartcoffee.modules.inventory.persistence.InventoryReconciliationQueries.OrderSnapshot;
import com.smartcoffee.modules.inventory.persistence.InventoryReconciliationQueries.PaymentSettlement;
import com.smartcoffee.modules.inventory.persistence.InventoryReservationRepository;
import com.smartcoffee.modules.inventory.persistence.StockTransactionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Slice 2E paid-but-unconsumed recovery tests (spec §9). Automatic consumption is
 * permitted ONLY when all nine conditions provably hold; otherwise the reservation
 * is left untouched for manual review. Crucially, when it does act it delegates to
 * the shared {@link InventoryReservationFinalizationService#consumeOrder} — never a
 * second consumption algorithm.
 */
class PaidOrderRecoveryServiceTest {

    private InventoryReservationRepository reservations;
    private StockTransactionRepository ledger;
    private InventoryBalanceRepository balances;
    private InventoryReconciliationQueries queries;
    private InventoryReservationFinalizationService finalization;
    private PaidOrderRecoveryService service;

    private UUID store;
    private UUID order;
    private UUID cart;
    private UUID item;
    private UUID actor;

    @BeforeEach
    void setup() {
        reservations = mock(InventoryReservationRepository.class);
        ledger = mock(StockTransactionRepository.class);
        balances = mock(InventoryBalanceRepository.class);
        queries = mock(InventoryReconciliationQueries.class);
        finalization = mock(InventoryReservationFinalizationService.class);
        service = new PaidOrderRecoveryService(reservations, ledger, balances, queries, finalization);

        store = UUID.randomUUID();
        order = UUID.randomUUID();
        cart = UUID.randomUUID();
        item = UUID.randomUUID();
        actor = UUID.randomUUID();
    }

    @Test
    void consumesExactlyOnceWhenAllNineConditionsHold() {
        InventoryReservation held = activeOrderHold("10");
        stubHappyPath(held);
        when(finalization.consumeOrder(order, actor)).thenReturn(true);

        assertEquals(PaidOrderRecoveryService.Decision.CONSUMED, service.recover(order, actor));

        // Reuses the settlement consumption primitive — no parallel algorithm.
        verify(finalization).consumeOrder(order, actor);
    }

    @Test
    void alreadyConsumedReservationIsAnIdempotentAlreadyConsumed() {
        InventoryReservation held = activeOrderHold("10");
        held.consumed(actor);
        when(reservations.lockByOrderId(order)).thenReturn(Optional.of(held));

        assertEquals(PaidOrderRecoveryService.Decision.ALREADY_CONSUMED, service.recover(order, actor));
        verifyNoInteractions(finalization);
    }

    @Test
    void noReservationIsManualReview() {
        when(reservations.lockByOrderId(order)).thenReturn(Optional.empty());
        assertEquals(PaidOrderRecoveryService.Decision.MANUAL_REVIEW, service.recover(order, actor));
        verifyNoInteractions(finalization);
    }

    @Test
    void releasedReservationIsNotConsumedByRecovery() {
        InventoryReservation held = activeOrderHold("10");
        held.released("ORDER_CANCELLED");
        when(reservations.lockByOrderId(order)).thenReturn(Optional.of(held));

        assertEquals(PaidOrderRecoveryService.Decision.MANUAL_REVIEW, service.recover(order, actor));
        verifyNoInteractions(finalization);
    }

    @Test
    void unresolvedOrderOrStoreMismatchIsManualReview() {
        InventoryReservation held = activeOrderHold("10");
        when(reservations.lockByOrderId(order)).thenReturn(Optional.of(held));
        when(queries.orderSnapshot(order)).thenReturn(null);

        assertEquals(PaidOrderRecoveryService.Decision.MANUAL_REVIEW, service.recover(order, actor));
        verifyNoInteractions(finalization);

        when(queries.orderSnapshot(order)).thenReturn(new OrderSnapshot(UUID.randomUUID(), "CONFIRMED"));
        assertEquals(PaidOrderRecoveryService.Decision.MANUAL_REVIEW, service.recover(order, actor),
                "a store mismatch is ambiguous — never consume on a guess");
        verifyNoInteractions(finalization);
    }

    @Test
    void notSettledPaymentIsManualReview() {
        InventoryReservation held = activeOrderHold("10");
        when(reservations.lockByOrderId(order)).thenReturn(Optional.of(held));
        when(queries.orderSnapshot(order)).thenReturn(new OrderSnapshot(store, "CONFIRMED"));
        when(queries.paymentSettlement(order)).thenReturn(PaymentSettlement.AMBIGUOUS);

        assertEquals(PaidOrderRecoveryService.Decision.MANUAL_REVIEW, service.recover(order, actor));
        verifyNoInteractions(finalization);
    }

    @Test
    void orderStatusIncompatibleWithSettlementIsManualReview() {
        InventoryReservation held = activeOrderHold("10");
        when(reservations.lockByOrderId(order)).thenReturn(Optional.of(held));
        when(queries.orderSnapshot(order)).thenReturn(new OrderSnapshot(store, "AWAITING_PAYMENT"));
        when(queries.paymentSettlement(order)).thenReturn(PaymentSettlement.SETTLED);

        assertEquals(PaidOrderRecoveryService.Decision.MANUAL_REVIEW, service.recover(order, actor));
        verifyNoInteractions(finalization);
    }

    @Test
    void existingConsumptionLedgerBlocksRecovery() {
        InventoryReservation held = activeOrderHold("10");
        stubHappyPath(held);
        when(ledger.existsByReservationIdAndType(held.id(), StockTransactionType.SALE_CONSUMPTION)).thenReturn(true);

        assertEquals(PaidOrderRecoveryService.Decision.MANUAL_REVIEW, service.recover(order, actor));
        verifyNoInteractions(finalization);
    }

    @Test
    void existingReleaseLedgerBlocksRecovery() {
        // Never consume a reservation that was already released (the §24 winner rule).
        InventoryReservation held = activeOrderHold("10");
        stubHappyPath(held);
        when(ledger.existsByReservationIdAndType(held.id(), StockTransactionType.SALE_RELEASE)).thenReturn(true);

        assertEquals(PaidOrderRecoveryService.Decision.MANUAL_REVIEW, service.recover(order, actor));
        verifyNoInteractions(finalization);
    }

    @Test
    void missingBalanceRowIsManualReview() {
        InventoryReservation held = activeOrderHold("10");
        stubHappyPath(held);
        when(balances.find(eq(store), any())).thenReturn(Optional.empty());

        assertEquals(PaidOrderRecoveryService.Decision.MANUAL_REVIEW, service.recover(order, actor));
        verifyNoInteractions(finalization);
    }

    @Test
    void guardedConsumptionNotSatisfiableIsManualReview() {
        InventoryReservation held = activeOrderHold("10");
        stubHappyPath(held);
        // reserved below the held quantity → a guarded consume would fail; leave it alone.
        InventoryBalance insufficient = balance("100", "5");
        when(balances.find(eq(store), eq(item))).thenReturn(Optional.of(insufficient));

        assertEquals(PaidOrderRecoveryService.Decision.MANUAL_REVIEW, service.recover(order, actor));
        verifyNoInteractions(finalization);
    }

    @Test
    void concurrentConsumptionSurfacesAsAlreadyConsumed() {
        InventoryReservation held = activeOrderHold("10");
        stubHappyPath(held);
        when(finalization.consumeOrder(order, actor)).thenReturn(false); // lost the race

        assertEquals(PaidOrderRecoveryService.Decision.ALREADY_CONSUMED, service.recover(order, actor));
    }

    // ------------------------------------------------------------------ fixtures

    private void stubHappyPath(InventoryReservation held) {
        when(reservations.lockByOrderId(order)).thenReturn(Optional.of(held));
        when(queries.orderSnapshot(order)).thenReturn(new OrderSnapshot(store, "CONFIRMED"));
        when(queries.paymentSettlement(order)).thenReturn(PaymentSettlement.SETTLED);
        when(ledger.existsByReservationIdAndType(held.id(), StockTransactionType.SALE_CONSUMPTION)).thenReturn(false);
        when(ledger.existsByReservationIdAndType(held.id(), StockTransactionType.SALE_RELEASE)).thenReturn(false);
        InventoryBalance ok = balance("100", "10");
        when(balances.find(eq(store), eq(item))).thenReturn(Optional.of(ok));
    }

    private InventoryReservation activeOrderHold(String quantity) {
        return InventoryReservation.fromCartHolds(store, order, cart, Instant.now().plusSeconds(600),
                Map.of(item, new BigDecimal(quantity)), Map.of(item, "G"), "key");
    }

    private static InventoryBalance balance(String current, String reserved) {
        // The recovery gate only reads the two quantities, so a stub balance is enough
        // (the aggregate exposes no reserved-stock mutator outside the guarded repo).
        InventoryBalance b = mock(InventoryBalance.class);
        when(b.currentStock()).thenReturn(new BigDecimal(current));
        when(b.reservedStock()).thenReturn(new BigDecimal(reserved));
        return b;
    }
}
