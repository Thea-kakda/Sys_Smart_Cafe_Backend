package com.smartcoffee.modules.inventory;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.modules.inventory.application.AdminReservationReleaseService.ReleaseResult;
import com.smartcoffee.modules.inventory.application.InventoryReconciliationService.ReservationDetailView;
import com.smartcoffee.modules.inventory.application.InventoryReconciliationService.RunResult;
import com.smartcoffee.modules.ordering.api.OrderResponse;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Slice 2E reconciliation, repair and administrative-release integration tests
 * (spec §22 E/G/H, plus §7 read-only detection and §13 reservation detail).
 *
 * <p>These exercise the real set-based detectors against migrated Postgres — the
 * FULL OUTER JOIN that compares recorded {@code reserved_stock} against the derived
 * SUM of ACTIVE reservation lines, and the join-based cancelled/paid detectors —
 * and prove the two load-bearing guarantees the spec insists on: a dry run writes
 * <b>no</b> stock/ledger/reservation mutation, and an automatic repair only ever
 * acts through the guarded shared primitives after revalidating live state.
 */
class ReservationReconciliationIT extends ReservationOpsTestBase {

    @BeforeEach
    void arrange() {
        seedStoreAndCustomer();
    }

    // ------------------------------------------------ Scenario E: balance drift

    @Test
    void dryRunDetectsReservedBalanceMismatchAndWritesNothing() {
        Latte l = latte();
        checkoutHeld(l);
        // Recorded reserved drifts above the derived ACTIVE-line sum (18 → 25).
        jdbc.update("update inventory.inventory_balances set reserved_stock = 25 where store_id = ? "
                + "and ingredient_id = ?", storeId, l.beans());

        RunResult dry = reconciliation.run(globalAdminActor(), storeId, true, null);

        assertTrue(dry.dryRun());
        assertTrue(dry.anomalyCount() >= 1, "mismatch must surface as a finding");
        String anomaly = jdbc.queryForObject("select anomaly_type from inventory.inventory_reconciliation_findings "
                + "where run_id = ?", String.class, dry.runId());
        assertEquals("RESERVED_BALANCE_MISMATCH", anomaly);
        // A dry run is a report only: nothing about the drifted balance moved.
        assertReserved(l.beans(), "25");
        assertOnHand(l.beans(), STOCK_ON_HAND);
        assertEquals(0L, ledgerCount("SALE_RELEASE"), "scan writes no ledger");
        assertEquals(0L, ledgerCount("SALE_CONSUMPTION"), "scan writes no ledger");
    }

    // -------------------------- auto-repair of an abandoned cancelled-order hold

    @Test
    void abandonedCancelledOrderHoldIsAutoRepairedThroughTheSharedPrimitive() {
        Latte l = latte();
        OrderResponse order = checkoutHeld(l);
        UUID resv = orderReservationId(order.orderId());
        // Crash-window fixture: the order is CANCELLED but its hold never released.
        jdbc.update("update ordering.orders set status = 'CANCELLED' where order_id = ?", order.orderId());

        // Dry run first: the abandoned hold is reported, nothing is released.
        RunResult dry = reconciliation.run(globalAdminActor(), storeId, true, null);
        assertTrue(dry.anomalyCount() >= 1);
        assertEquals("ACTIVE", reservationStatus(resv));
        assertReserved(l.beans(), "18");
        assertEquals(0L, ledgerCount("SALE_RELEASE", resv), "dry run releases nothing");

        // Opting into repair releases the abandoned hold exactly once, via the guarded
        // finalization primitive (reason DATA_RECOVERY), never a generic recalculation.
        RunResult repair = reconciliation.run(globalAdminActor(), storeId, false, null);
        assertTrue(repair.repairedCount() >= 1, "the abandoned hold is auto-repairable");
        assertEquals("RELEASED", reservationStatus(resv));
        assertReserved(l.beans(), "0");
        assertOnHand(l.beans(), STOCK_ON_HAND);
        assertEquals(1L, ledgerCount("SALE_RELEASE", resv));
        assertEquals(0L, ledgerCount("SALE_CONSUMPTION", resv), "a release never also consumes");
    }

    // ------------------------------------------------------ §13 reservation detail

    @Test
    void reservationDetailReportsSnapshotWithoutPersonalData() {
        Latte l = latte();
        OrderResponse order = checkoutHeld(l);
        UUID resv = orderReservationId(order.orderId());

        ReservationDetailView detail = reconciliation.reservationDetail(globalAdminActor(), resv);

        assertEquals(resv, detail.reservationId());
        assertEquals(storeId, detail.storeId());
        assertEquals("ACTIVE", detail.status());
        assertEquals(order.orderId(), detail.orderId());
        assertEquals(3, detail.lines().size(), "the three recipe ingredients are snapshotted");
        assertTrue(detail.detectedAnomalies().isEmpty(), "a clean hold reports no anomaly");
    }

    // --------------------------------------------------- Scenario G: admin release

    @Test
    void administratorReleasesActiveHoldOnceAndStaysIdempotent() {
        Latte l = latte();
        OrderResponse order = checkoutHeld(l);
        UUID resv = orderReservationId(order.orderId());
        long version = reservationVersion(resv);

        ReleaseResult first = release.release(globalAdminActor(), resv, version, "operator cleanup",
                UUID.randomUUID());

        assertTrue(first.released());
        assertEquals("RELEASED", reservationStatus(resv));
        assertReserved(l.beans(), "0");
        assertOnHand(l.beans(), STOCK_ON_HAND);
        assertEquals(1L, ledgerCount("SALE_RELEASE", resv));

        // The hold is terminal now: a repeated call is a stable no-op, not a second release.
        ReleaseResult again = release.release(globalAdminActor(), resv, version, "operator cleanup",
                UUID.randomUUID());
        assertFalse(again.released());
        assertEquals(1L, ledgerCount("SALE_RELEASE", resv));
    }

    @Test
    void consumedReservationCannotBeAdministrativelyReleased() {
        Latte l = latte();
        OrderResponse order = checkoutHeld(l);
        UUID resv = orderReservationId(order.orderId());
        settle(order.orderId(), "20");
        assertEquals("CONSUMED", reservationStatus(resv));

        assertThrows(com.smartcoffee.common.exception.ConflictException.class,
                () -> release.release(globalAdminActor(), resv, reservationVersion(resv), "too late",
                        UUID.randomUUID()));
        assertEquals("CONSUMED", reservationStatus(resv));
    }

    // -------------------------------------------------- Scenario H: store isolation

    @Test
    void managerOutsideTheStoreScopeCannotRelease() {
        Latte l = latte();
        OrderResponse order = checkoutHeld(l);
        UUID resv = orderReservationId(order.orderId());
        CurrentActor otherStoreManager = managerActor(UUID.randomUUID()); // scoped to an unrelated store

        assertThrows(ForbiddenException.class,
                () -> release.release(otherStoreManager, resv, null, "poach", UUID.randomUUID()));

        assertEquals("ACTIVE", reservationStatus(resv));
        assertReserved(l.beans(), "18");
        assertEquals(0L, ledgerCount("SALE_RELEASE", resv), "a rejected release mutates nothing");
    }

    @Test
    void crossStoreReconciliationIsAdminOnly() {
        Latte l = latte();
        checkoutHeld(l);
        CurrentActor storeScoped = managerActor(storeId); // has reconciliation:run but no global access

        // A null storeId is a global scan; it must be refused for a store-only manager.
        assertThrows(com.smartcoffee.common.exception.ValidationException.class,
                () -> reconciliation.run(storeScoped, null, true, null));
    }
}
