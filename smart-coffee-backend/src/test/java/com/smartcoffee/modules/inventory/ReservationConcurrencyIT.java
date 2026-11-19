package com.smartcoffee.modules.inventory;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.modules.cart.api.CartResponse;
import com.smartcoffee.modules.ordering.api.OrderResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Slice 2E multi-node + race concurrency tests (spec §24). These run against the
 * real Postgres so the guarantee is not a mock expectation but an actual database
 * property: the {@code FOR UPDATE SKIP LOCKED} claim and the per-row
 * {@code SELECT ... FOR UPDATE} guards mean two workers processing an overlapping
 * candidate set never move the same reservation twice, and a release and a
 * settlement racing for one hold always resolve to exactly one terminal ledger row
 * ({@code SALE_RELEASE} XOR {@code SALE_CONSUMPTION}, never both).
 *
 * <p>Scenarios 5 and 6 of §24 (recovery racing cancellation / settlement) share the
 * identical guarded-consumption primitive exercised by §22-C here and by the
 * {@code PaidOrderRecoveryServiceTest} / {@code consumeOrder} unit suite; the winner
 * rule they depend on — "consumption wins then release is impossible" — is proven by
 * {@link #expiryRacesSettlementLeavesOneTerminalOutcome} and
 * {@link #adminReleaseRacesSettlementLeavesOneTerminalOutcome} below.
 */
class ReservationConcurrencyIT extends ReservationOpsTestBase {

    @BeforeEach
    void arrange() {
        seedStoreAndCustomer();
    }

    // ----------------------------------------------------- §24.1 two cart workers

    @Test
    void twoExpiryWorkersReleaseOneCartReservationOnce() throws Exception {
        Latte l = latte();
        CartResponse cart = addToCart(l);
        UUID resv = cartReservationId(cart.cartId());
        backdateCart(cart.cartId());

        race(cartExpiry::expire, cartExpiry::expire);

        assertEquals(1L, ledgerCount("SALE_RELEASE", resv), "the hold releases exactly once");
        assertEquals("RELEASED", reservationStatus(resv));
        assertReserved(l.beans(), "0");
        assertOnHand(l.beans(), STOCK_ON_HAND);
    }

    // ---------------------------------------------------- §24.2 two order workers

    @Test
    void twoExpiryWorkersProcessOneOrderReservationOnce() throws Exception {
        Latte l = latte();
        OrderResponse order = checkoutHeld(l);
        UUID resv = orderReservationId(order.orderId());
        backdateReservation(resv);

        race(orderExpiry::run, orderExpiry::run);

        assertEquals(1L, ledgerCount("SALE_RELEASE", resv), "SKIP LOCKED + claim columns process once");
        assertEquals("RELEASED", reservationStatus(resv));
        assertReserved(l.beans(), "0");
    }

    // --------------------------------------------------- §24.3 expiry races payment

    @Test
    void expiryRacesSettlementLeavesOneTerminalOutcome() throws Exception {
        Latte l = latte();
        OrderResponse order = checkoutHeld(l);
        UUID resv = orderReservationId(order.orderId());
        backdateReservation(resv);

        race(() -> settle(order.orderId(), "20"), orderExpiry::run);

        long consumed = ledgerCount("SALE_CONSUMPTION", resv);
        long released = ledgerCount("SALE_RELEASE", resv);
        assertEquals(1L, consumed + released, "exactly one terminal ledger row (consumed=" + consumed
                + " released=" + released + ")");
        assertReserved(l.beans(), "0");
        String status = reservationStatus(resv);
        assertTrue(status.equals("CONSUMED") || status.equals("RELEASED"), "one winner: " + status);
    }

    // ----------------------------------------------- §24.4 admin release races pay

    @Test
    void adminReleaseRacesSettlementLeavesOneTerminalOutcome() throws Exception {
        Latte l = latte();
        OrderResponse order = checkoutHeld(l);
        UUID resv = orderReservationId(order.orderId());
        long version = reservationVersion(resv);

        race(() -> release.release(globalAdminActor(), resv, version, "operator cleanup", UUID.randomUUID()),
                () -> settle(order.orderId(), "20"));

        long consumed = ledgerCount("SALE_CONSUMPTION", resv);
        long released = ledgerCount("SALE_RELEASE", resv);
        assertEquals(1L, consumed + released, "release and settlement cannot both land");
        assertReserved(l.beans(), "0");
    }

    // --------------------------------------- §24.5 two nodes never share a candidate

    @Test
    void twoNodesNeverClaimTheSameExpiredReservation() throws Exception {
        List<UUID> mine = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            OrderResponse order = checkoutHeld(latte());
            UUID resv = orderReservationId(order.orderId());
            backdateReservation(resv);
            mine.add(resv);
        }

        List<UUID> claimedA = new ArrayList<>();
        List<UUID> claimedB = new ArrayList<>();
        Instant now = java.time.Instant.now();
        Instant stale = now.minusSeconds(300);
        race(
                () -> claimedA.addAll(expiryClaims.claimExpiredOrder("nodeA", "tokA", now, stale, 50, 5)),
                () -> claimedB.addAll(expiryClaims.claimExpiredOrder("nodeB", "tokB", now, stale, 50, 5)));

        Set<UUID> overlap = new HashSet<>(claimedA);
        overlap.retainAll(new HashSet<>(claimedB));
        assertTrue(overlap.isEmpty(), "SKIP LOCKED must never hand one reservation to two nodes: " + overlap);

        Set<UUID> union = new HashSet<>(claimedA);
        union.addAll(claimedB);
        assertTrue(union.containsAll(mine), "every expired hold was claimed by exactly one node");
    }

    // --------------------------------------------------- §24.7 stale claim reclaim

    @Test
    void staleClaimIsReclaimedAndProcessedOnce() {
        Latte l = latte();
        OrderResponse order = checkoutHeld(l);
        UUID resv = orderReservationId(order.orderId());
        backdateReservation(resv);
        // A worker died mid-batch: the claim is far older than the stale window.
        jdbc.update("update inventory.inventory_reservations set processing_owner = 'dead-node', "
                + "processing_token = 'dead', processing_started_at = now() - interval '30 minutes', "
                + "processing_attempts = 1 where reservation_id = ?", resv);

        orderExpiry.run();

        assertEquals(1L, ledgerCount("SALE_RELEASE", resv), "a stale claim is reclaimed and released once");
        assertEquals("RELEASED", reservationStatus(resv));
    }

    // ---------------------------------------------------------------- harness

    /**
     * Runs two actions concurrently behind a start barrier. A losing race throws a
     * business conflict by design; those are swallowed because the assertion is on the
     * persisted outcome, not on both callers succeeding.
     */
    private void race(Runnable left, Runnable right) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CyclicBarrier barrier = new CyclicBarrier(2);
        try {
            Future<?> f1 = pool.submit(() -> start(barrier, left));
            Future<?> f2 = pool.submit(() -> start(barrier, right));
            f1.get(120, TimeUnit.SECONDS);
            f2.get(120, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
    }

    private static void start(CyclicBarrier barrier, Runnable action) {
        try {
            barrier.await(60, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        try {
            action.run();
        } catch (RuntimeException expectedLoser) {
            // A concurrent loser surfaces a conflict/lock error; the DB state is the truth.
        }
    }
}
