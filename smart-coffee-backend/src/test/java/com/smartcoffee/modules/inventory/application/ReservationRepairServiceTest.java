package com.smartcoffee.modules.inventory.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.modules.audit.application.AuditWriter;
import com.smartcoffee.modules.inventory.domain.InventoryReservation;
import com.smartcoffee.modules.inventory.domain.reconciliation.ReconciliationFinding;
import com.smartcoffee.modules.inventory.domain.reconciliation.ReservationAnomalyType;
import com.smartcoffee.modules.inventory.persistence.InventoryReconciliationQueries;
import com.smartcoffee.modules.inventory.persistence.InventoryReconciliationQueries.OrderSnapshot;
import com.smartcoffee.modules.inventory.persistence.InventoryReservationRepository;
import com.smartcoffee.modules.inventory.persistence.ReconciliationFindingRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Slice 2E repair-service tests (spec §10/§11/§27 item 7). The golden rules: a
 * finding is revalidated against the LIVE row before anything moves; a stale subject
 * is superseded, an ambiguous one demoted to manual review; a release/consumption
 * delegates to the shared primitives (never a second algorithm); a manual repair
 * requires a reason; and re-repairing an already-repaired finding is a no-op.
 */
class ReservationRepairServiceTest {

    private ReconciliationFindingRepository findings;
    private InventoryReservationRepository reservations;
    private InventoryReconciliationQueries queries;
    private InventoryReservationFinalizationService finalization;
    private PaidOrderRecoveryService recovery;
    private AuditWriter audit;
    private ReservationMetrics metrics;
    private ReservationRepairService service;

    private UUID run;
    private UUID store;
    private UUID item;
    private UUID orderId;
    private UUID cart;
    private UUID actor;

    @BeforeEach
    void setup() {
        findings = mock(ReconciliationFindingRepository.class);
        reservations = mock(InventoryReservationRepository.class);
        queries = mock(InventoryReconciliationQueries.class);
        finalization = mock(InventoryReservationFinalizationService.class);
        recovery = mock(PaidOrderRecoveryService.class);
        audit = mock(AuditWriter.class);
        metrics = mock(ReservationMetrics.class);
        service = new ReservationRepairService(findings, reservations, queries, finalization, recovery,
                new RepairPolicy(), audit, metrics);
        when(findings.save(any())).thenAnswer(i -> i.getArgument(0));

        run = UUID.randomUUID();
        store = UUID.randomUUID();
        item = UUID.randomUUID();
        orderId = UUID.randomUUID();
        cart = UUID.randomUUID();
        actor = UUID.randomUUID();
    }

    @Test
    void repairsCancelledOrderReleaseAndAudits() {
        InventoryReservation held = activeOrderHold();
        ReconciliationFinding f = repairable(ReservationAnomalyType.ACTIVE_ORDER_RESERVATION_ON_CANCELLED_ORDER, held);
        when(findings.findById(f.id())).thenReturn(Optional.of(f));
        when(reservations.lock(held.id())).thenReturn(Optional.of(held));
        when(queries.orderSnapshot(orderId)).thenReturn(new OrderSnapshot(store, "CANCELLED"));
        when(finalization.releaseOrder(eq(orderId), any(), any())).thenReturn(true);

        assertEquals(ReservationRepairService.Outcome.REPAIRED, service.repair(f.id(), null, "auto", "K-1"));

        verify(finalization).releaseOrder(eq(orderId), any(), any());
        verify(audit).system(eq(store), any(), any(), any(), eq(f.id()), any(), any(), any());
    }

    @Test
    void manualOnlyAnomalyIsDemotedWithoutTouchingStock() {
        ReconciliationFinding f = open(ReservationAnomalyType.RESERVED_BALANCE_MISMATCH);
        when(findings.findById(f.id())).thenReturn(Optional.of(f));

        assertEquals(ReservationRepairService.Outcome.MANUAL_REVIEW, service.repair(f.id(), null, "auto", "K-2"));

        verifyNoInteractions(finalization, recovery, reservations);
    }

    @Test
    void staleSubjectIsSupersededNotRepaired() {
        InventoryReservation released = activeOrderHold();
        released.released("ORDER_CANCELLED");
        ReconciliationFinding f = repairable(ReservationAnomalyType.ACTIVE_ORDER_RESERVATION_ON_CANCELLED_ORDER,
                released);
        when(findings.findById(f.id())).thenReturn(Optional.of(f));
        when(reservations.lock(released.id())).thenReturn(Optional.of(released));

        assertEquals(ReservationRepairService.Outcome.SUPERSEDED, service.repair(f.id(), null, "auto", "K-3"));
        verifyNoInteractions(finalization);
    }

    @Test
    void vanishedSubjectIsSuperseded() {
        InventoryReservation held = activeOrderHold();
        ReconciliationFinding f = repairable(ReservationAnomalyType.ACTIVE_ORDER_RESERVATION_ON_CANCELLED_ORDER, held);
        when(findings.findById(f.id())).thenReturn(Optional.of(f));
        when(reservations.lock(any())).thenReturn(Optional.empty());

        assertEquals(ReservationRepairService.Outcome.SUPERSEDED, service.repair(f.id(), null, "auto", "K-4"));
        verifyNoInteractions(finalization);
    }

    @Test
    void releaseRevalidationDemotesWhenOrderIsNoLongerCancelled() {
        InventoryReservation held = activeOrderHold();
        ReconciliationFinding f = repairable(ReservationAnomalyType.ACTIVE_ORDER_RESERVATION_ON_CANCELLED_ORDER, held);
        when(findings.findById(f.id())).thenReturn(Optional.of(f));
        when(reservations.lock(held.id())).thenReturn(Optional.of(held));
        when(queries.orderSnapshot(orderId)).thenReturn(new OrderSnapshot(store, "CONFIRMED"));

        assertEquals(ReservationRepairService.Outcome.MANUAL_REVIEW, service.repair(f.id(), null, "auto", "K-5"));
        verify(finalization, never()).releaseOrder(any(), any(), any());
    }

    @Test
    void paidConsumptionDelegatesToGuardedRecovery() {
        InventoryReservation held = activeOrderHold();
        ReconciliationFinding f = repairable(ReservationAnomalyType.ACTIVE_ORDER_RESERVATION_ON_PAID_ORDER, held);
        when(findings.findById(f.id())).thenReturn(Optional.of(f));
        when(reservations.lock(held.id())).thenReturn(Optional.of(held));
        when(recovery.recover(eq(orderId), any())).thenReturn(PaidOrderRecoveryService.Decision.CONSUMED);

        assertEquals(ReservationRepairService.Outcome.REPAIRED, service.repair(f.id(), null, "auto", "K-6"));
        verify(recovery).recover(eq(orderId), any());
    }

    @Test
    void paidConsumptionDegradesToManualWhenRecoveryIsAmbiguous() {
        InventoryReservation held = activeOrderHold();
        ReconciliationFinding f = repairable(ReservationAnomalyType.ACTIVE_ORDER_RESERVATION_ON_PAID_ORDER, held);
        when(findings.findById(f.id())).thenReturn(Optional.of(f));
        when(reservations.lock(held.id())).thenReturn(Optional.of(held));
        when(recovery.recover(eq(orderId), any())).thenReturn(PaidOrderRecoveryService.Decision.MANUAL_REVIEW);

        assertEquals(ReservationRepairService.Outcome.MANUAL_REVIEW, service.repair(f.id(), null, "auto", "K-7"));
    }

    @Test
    void alreadyConsumedPaidOrderIsNoChange() {
        InventoryReservation held = activeOrderHold();
        ReconciliationFinding f = repairable(ReservationAnomalyType.ACTIVE_ORDER_RESERVATION_ON_PAID_ORDER, held);
        when(findings.findById(f.id())).thenReturn(Optional.of(f));
        when(reservations.lock(held.id())).thenReturn(Optional.of(held));
        when(recovery.recover(eq(orderId), any())).thenReturn(PaidOrderRecoveryService.Decision.ALREADY_CONSUMED);

        assertEquals(ReservationRepairService.Outcome.NO_CHANGE, service.repair(f.id(), null, "auto", "K-8"));
    }

    @Test
    void reRepairingAnAlreadyRepairedFindingIsANoOp() {
        ReconciliationFinding f = open(ReservationAnomalyType.ACTIVE_ORDER_RESERVATION_ON_CANCELLED_ORDER);
        f.markRepaired(actor, "done", "K-old", Instant.now());
        when(findings.findById(f.id())).thenReturn(Optional.of(f));

        assertEquals(ReservationRepairService.Outcome.NO_CHANGE, service.repair(f.id(), actor, "again", "K-9"));
        verifyNoInteractions(reservations, finalization, recovery);
    }

    @Test
    void aManualReviewFindingCannotBeRepaired() {
        ReconciliationFinding f = open(ReservationAnomalyType.ACTIVE_ORDER_RESERVATION_ON_CANCELLED_ORDER);
        f.markManualReview();
        when(findings.findById(f.id())).thenReturn(Optional.of(f));

        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.repair(f.id(), actor, "reason", "K-10"));
        assertEquals("FINDING_NOT_REPAIRABLE", ex.code());
    }

    @Test
    void manualRepairWithoutReasonIsRejected() {
        InventoryReservation held = activeOrderHold();
        ReconciliationFinding f = repairable(ReservationAnomalyType.ACTIVE_ORDER_RESERVATION_ON_CANCELLED_ORDER, held);
        when(findings.findById(f.id())).thenReturn(Optional.of(f));

        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.repair(f.id(), actor, "  ", "K-11"));
        assertEquals("REPAIR_REASON_REQUIRED", ex.code());
    }

    @Test
    void missingFindingIsAConflict() {
        when(findings.findById(any())).thenReturn(Optional.empty());
        assertThrows(ConflictException.class, () -> service.repair(UUID.randomUUID(), null, "auto", "K-12"));
    }

    // ------------------------------------------------------------------ fixtures

    private ReconciliationFinding open(ReservationAnomalyType type) {
        return ReconciliationFinding.open(run, store, item, UUID.randomUUID(), type, BigDecimal.TEN, BigDecimal.ONE,
                orderId, cart, null, Instant.now());
    }

    /** A REPAIRABLE finding whose subject is the given reservation (id must match for the lock). */
    private ReconciliationFinding repairable(ReservationAnomalyType type, InventoryReservation held) {
        ReconciliationFinding f = ReconciliationFinding.open(run, store, item, held.id(), type, BigDecimal.TEN,
                BigDecimal.ONE, orderId, cart, null, Instant.now());
        f.markRepairable();
        return f;
    }

    private InventoryReservation activeOrderHold() {
        return InventoryReservation.fromCartHolds(store, orderId, cart, Instant.now().plusSeconds(600),
                Map.of(item, BigDecimal.TEN), Map.of(item, "G"), "key");
    }
}
