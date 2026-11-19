package com.smartcoffee.modules.inventory.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.common.security.StoreAuthorizationService;
import com.smartcoffee.infrastructure.retry.TransientRetryExecutor;
import com.smartcoffee.modules.inventory.domain.reconciliation.ReconciliationFinding;
import com.smartcoffee.modules.inventory.domain.reconciliation.ReservationAnomalyType;
import com.smartcoffee.modules.inventory.persistence.InventoryReconciliationQueries;
import com.smartcoffee.modules.inventory.persistence.InventoryReservationRepository;
import com.smartcoffee.modules.inventory.persistence.ReconciliationFindingRepository;
import com.smartcoffee.modules.inventory.persistence.ReconciliationRow;
import com.smartcoffee.modules.inventory.persistence.ReconciliationRunRepository;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * Slice 2E reconciliation-service tests (spec §7/§13/§27 item 6). Focus on the
 * guarantees that make reconciliation safe to run against production: authorization
 * and store scoping are enforced in the service; a dry run writes findings but NEVER
 * repairs; the repair pass reuses the guarded repair service in its own transaction;
 * and the derived-vs-recorded comparison decides the correct anomaly shape.
 */
class InventoryReconciliationServiceTest {

    private InventoryReconciliationQueries queries;
    private ReconciliationRunRepository runs;
    private ReconciliationFindingRepository findings;
    private InventoryReservationRepository reservations;
    private ReservationRepairService repair;
    private TransientRetryExecutor retry;
    private ReservationMetrics metrics;
    private PermissionEvaluator permissions;
    private StoreAuthorizationService stores;
    private InventoryReconciliationService service;

    private final Clock clock = Clock.fixed(Instant.parse("2026-10-27T10:00:00Z"), ZoneOffset.UTC);
    private UUID store;
    private UUID item;
    private UUID resv;
    private UUID order;
    private CurrentActor storeActor;
    private CurrentActor globalActor;

    @BeforeEach
    void setup() {
        queries = mock(InventoryReconciliationQueries.class);
        runs = mock(ReconciliationRunRepository.class);
        findings = mock(ReconciliationFindingRepository.class);
        reservations = mock(InventoryReservationRepository.class);
        repair = mock(ReservationRepairService.class);
        retry = mock(TransientRetryExecutor.class);
        metrics = mock(ReservationMetrics.class);
        permissions = mock(PermissionEvaluator.class);
        stores = mock(StoreAuthorizationService.class);
        service = new InventoryReconciliationService(queries, runs, findings, reservations, new RepairPolicy(), repair,
                retry, metrics, clock, permissions, stores, 200);

        // Let timing/retry wrappers actually run the supplied unit of work.
        when(metrics.time(any(), any())).thenAnswer(i -> ((Supplier<?>) i.getArgument(1)).get());
        when(retry.execute(any(), any())).thenAnswer(i -> ((Supplier<?>) i.getArgument(1)).get());

        store = UUID.randomUUID();
        item = UUID.randomUUID();
        resv = UUID.randomUUID();
        order = UUID.randomUUID();
        storeActor = new CurrentActor(UUID.randomUUID(), "STAFF",
                java.util.Set.of(PermissionCodes.RECONCILIATION_RUN, PermissionCodes.RECONCILIATION_VIEW,
                        PermissionCodes.RECONCILIATION_REPAIR), java.util.Set.of(store), false);
        globalActor = new CurrentActor(UUID.randomUUID(), "ADMIN",
                java.util.Set.of(PermissionCodes.RECONCILIATION_RUN, PermissionCodes.RECONCILIATION_VIEW,
                        PermissionCodes.RECONCILIATION_REPAIR), java.util.Set.of(), true);
    }

    // ------------------------------------------------------------------- pure

    @Test
    void clampMaxItemsDefaultsAndCaps() {
        assertEquals(500, InventoryReconciliationService.clampMaxItems(null));
        assertEquals(500, InventoryReconciliationService.clampMaxItems(0));
        assertEquals(500, InventoryReconciliationService.clampMaxItems(-5));
        assertEquals(150, InventoryReconciliationService.clampMaxItems(150));
        assertEquals(5000, InventoryReconciliationService.clampMaxItems(999999), "hard cap for a single scan");
    }

    // ---------------------------------------------------------------- gates

    @Test
    void globalScanRequiresAdmin() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.run(storeActor, null, true, null));
        assertEquals("GLOBAL_SCAN_REQUIRES_ADMIN", ex.code());
        verify(runs, never()).saveAndFlush(any());
    }

    @Test
    void globalAdminRunsACrossStoreScanWithoutStoreScoping() {
        UUID otherStore = UUID.randomUUID();
        when(queries.listActiveStoreIds()).thenReturn(List.of(store, otherStore));

        InventoryReconciliationService.RunResult out = service.run(globalActor, null, true, null);

        assertEquals(0, out.anomalyCount(), "no anomalies reported by the detectors");
        assertTrue(out.dryRun());
        verify(queries).countReservations(store);
        verify(queries).countReservations(otherStore);
        verify(stores, never()).requireStore(any(), any());
    }

    @Test
    void storeScanRespectsStoreScope() {
        doThrow(new ForbiddenException("STORE_ACCESS_DENIED", "denied")).when(stores).requireStore(storeActor, store);
        assertThrows(ForbiddenException.class, () -> service.run(storeActor, store, true, null));
        verify(runs, never()).saveAndFlush(any());
    }

    @Test
    void crossStoreViewRequiresAdmin() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.pageFindings(storeActor, null, 0, 20));
        assertEquals("CROSS_STORE_VIEW_REQUIRES_ADMIN", ex.code());
    }

    @Test
    void storeFindingsArePaginated() {
        ReconciliationFinding f = ReconciliationFinding.open(UUID.randomUUID(), store, item, resv,
                ReservationAnomalyType.RESERVED_BALANCE_MISMATCH, BigDecimal.TEN, BigDecimal.ONE, order, null, null,
                clock.instant());
        Page<ReconciliationFinding> page = new PageImpl<>(List.of(f));
        when(findings.openForStore(eq(store), any(Pageable.class))).thenReturn(page);

        Page<ReconciliationFinding> out = service.pageFindings(storeActor, store, 0, 20);

        assertEquals(1, out.getTotalElements());
        verify(stores).requireStore(storeActor, store);
    }

    @Test
    void repairRunRequiresNonEmptyReason() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.repairRun(storeActor, UUID.randomUUID(), "  ", UUID.randomUUID()));
        assertEquals("REPAIR_REASON_REQUIRED", ex.code());
    }

    @Test
    void repairRunOfUnknownRunIsNotFound() {
        when(runs.findById(any())).thenReturn(Optional.empty());
        assertThrows(ResourceNotFoundException.class,
                () -> service.repairRun(storeActor, UUID.randomUUID(), "fix it", UUID.randomUUID()));
    }

    // --------------------------------------------------------- scan behaviour

    @Test
    void dryRunWritesFindingsButNeverRepairs() {
        when(queries.activeOrderOnCancelledOrder(eq(store), anyInt()))
                .thenReturn(List.of(row(ReservationAnomalyType.ACTIVE_ORDER_RESERVATION_ON_CANCELLED_ORDER)));

        InventoryReconciliationService.RunResult out = service.run(storeActor, store, true, null);

        assertEquals(1, out.anomalyCount());
        assertEquals(1, out.automaticallyRepairableCount(), "a cancelled-order hold is a repair candidate");
        assertEquals(0, out.repairedCount());
        verify(repair, never()).repair(any(), any(), any(), any());
    }

    @Test
    void nonDryRunRepairsCandidatesThroughTheGuardedService() {
        when(queries.activeOrderOnCancelledOrder(eq(store), anyInt()))
                .thenReturn(List.of(row(ReservationAnomalyType.ACTIVE_ORDER_RESERVATION_ON_CANCELLED_ORDER)));
        when(repair.repair(any(), isNull(), any(), any())).thenReturn(ReservationRepairService.Outcome.REPAIRED);

        InventoryReconciliationService.RunResult out = service.run(storeActor, store, false, null);

        assertEquals(1, out.repairedCount());
        assertEquals(0, out.failedRepairCount());
        verify(repair, times(1)).repair(any(), isNull(), any(), any());
    }

    @Test
    void failedRepairIsCountedAndDoesNotAbortTheRun() {
        when(queries.activeOrderOnCancelledOrder(eq(store), anyInt()))
                .thenReturn(List.of(row(ReservationAnomalyType.ACTIVE_ORDER_RESERVATION_ON_CANCELLED_ORDER)));
        doThrow(new RuntimeException("db boom")).when(retry).execute(any(), any());

        InventoryReconciliationService.RunResult out = service.run(storeActor, store, false, null);

        assertEquals(0, out.repairedCount());
        assertEquals(1, out.failedRepairCount());
    }

    @Test
    void manualOnlyAnomaliesCountAsManualReviewNotRepairable() {
        when(queries.duplicateConsumption(eq(store), anyInt()))
                .thenReturn(List.of(row(ReservationAnomalyType.DUPLICATE_CONSUMPTION_LEDGER)));

        InventoryReconciliationService.RunResult out = service.run(storeActor, store, true, null);

        assertEquals(1, out.anomalyCount());
        assertEquals(0, out.automaticallyRepairableCount());
        assertEquals(1, out.manuallyReviewableCount());
    }

    // --------------------------------------------------- derived-vs-recorded

    @Test
    void balanceComparisonDecidesTheCorrectAnomalyShape() {
        UUID other = UUID.randomUUID();
        when(queries.balanceVsDerived(eq(store), anyInt())).thenReturn(List.of(
                new ReconciliationRow(store, item, null, null, null, new BigDecimal("10"), null), // recorded null
                new ReconciliationRow(store, other, null, null, null, BigDecimal.ZERO, new BigDecimal("4")))); // derived 0

        service.run(storeActor, store, true, null);

        ArgumentCaptor<List<ReconciliationFinding>> captor = ArgumentCaptor.captor();
        verify(findings).saveAll(captor.capture());
        List<ReconciliationFinding> saved = captor.getValue();

        assertTrue(saved.stream().anyMatch(f -> f.anomalyType()
                == ReservationAnomalyType.ACTIVE_RESERVATION_WITHOUT_BALANCE), "recorded side missing");
        assertTrue(saved.stream().anyMatch(f -> f.anomalyType()
                == ReservationAnomalyType.BALANCE_WITHOUT_ACTIVE_RESERVATION), "derived side zero");
    }

    @Test
    void bothSidesPresentIsABalanceMismatch() {
        when(queries.balanceVsDerived(eq(store), anyInt()))
                .thenReturn(List.of(new ReconciliationRow(store, item, null, null, null,
                        new BigDecimal("10"), new BigDecimal("7"))));

        service.run(storeActor, store, true, null);

        ArgumentCaptor<List<ReconciliationFinding>> captor = ArgumentCaptor.captor();
        verify(findings).saveAll(captor.capture());
        assertEquals(ReservationAnomalyType.RESERVED_BALANCE_MISMATCH, captor.getValue().get(0).anomalyType());
    }

    // ---------------------------------------------------------------- fixtures

    private ReconciliationRow row(ReservationAnomalyType type) {
        return new ReconciliationRow(store, item, resv, order, null, null, null);
    }
}
