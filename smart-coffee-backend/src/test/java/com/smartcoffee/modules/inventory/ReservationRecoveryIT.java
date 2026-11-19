package com.smartcoffee.modules.inventory;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.modules.cart.api.CartResponse;
import com.smartcoffee.modules.inventory.application.PaidOrderRecoveryService.Decision;
import com.smartcoffee.modules.inventory.application.ReservationReasons;
import com.smartcoffee.modules.ordering.api.OrderResponse;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Slice 2E integration scenarios A–D (spec §22): the reservation workers and the
 * paid-but-unconsumed recovery running against a real migrated Postgres and the
 * real inventory ledger. Each test arranges genuine DB state (add-to-cart, checkout,
 * cash settlement), performs any necessary SQL state-surgery to reach a crash-window
 * fixture, invokes the SAME entry points the scheduler would (scheduling is disabled
 * under the {@code test} profile), then asserts the persisted outcome.
 *
 * <ul>
 *   <li>A — an elapsed cart hold is released exactly once, current stock untouched.</li>
 *   <li>B — an elapsed unpaid order hold is released exactly once, never consumed.</li>
 *   <li>C — a paid-but-unconsumed order consumes exactly once via the shared path, and
 *       a second recovery is an idempotent no-op.</li>
 *   <li>D — an ambiguous/unsettled order is never auto-consumed: recovery declines and
 *       nothing about the reservation or stock changes.</li>
 * </ul>
 */
class ReservationRecoveryIT extends ReservationOpsTestBase {

    @BeforeEach
    void arrange() {
        seedStoreAndCustomer();
    }

    // ------------------------------------------------------- Scenario A: cart

    @Test
    void expiredCartReservationReleasesExactlyOnce() {
        Latte l = latte();
        CartResponse cart = addToCart(l);
        UUID resv = cartReservationId(cart.cartId());
        assertEquals("ACTIVE", reservationStatus(resv));

        backdateCart(cart.cartId());
        cartExpiry.expire();

        assertEquals("RELEASED", reservationStatus(resv));
        assertEquals("CART_EXPIRED", reservationColumn(resv, "release_reason"));
        assertEquals("EXPIRED", cartStatus(cart.cartId()));
        assertReserved(l.beans(), "0");
        assertReserved(l.milk(), "0");
        assertReserved(l.cup(), "0");
        assertOnHand(l.beans(), STOCK_ON_HAND); // release never touches on-hand stock
        assertEquals(1L, ledgerCount("SALE_RELEASE", resv));

        // A second sweep finds the cart no longer ACTIVE: nothing is released again.
        cartExpiry.expire();
        assertEquals(1L, ledgerCount("SALE_RELEASE", resv));
        assertEquals(0L, ledgerCount("SALE_CONSUMPTION"), "expiry never consumes");
    }

    // -------------------------------------------------- Scenario B: unpaid order

    @Test
    void expiredUnpaidOrderReservationReleasesOnceAndNeverConsumes() {
        Latte l = latte();
        OrderResponse order = checkoutHeld(l);
        UUID resv = orderReservationId(order.orderId());

        backdateReservation(resv);
        orderExpiry.run();

        assertEquals("RELEASED", reservationStatus(resv));
        assertEquals(ReservationReasons.ORDER_RESERVATION_EXPIRED, reservationColumn(resv, "release_reason"));
        assertReserved(l.beans(), "0");
        assertOnHand(l.beans(), STOCK_ON_HAND);
        assertEquals(1L, ledgerCount("SALE_RELEASE", resv));
        assertEquals(0L, ledgerCount("SALE_CONSUMPTION", resv));
        assertNotEquals("AWAITING_PAYMENT", orderStatus(order.orderId()), "the order followed the expiry rule");

        // Re-running the worker cannot release the (now terminal) hold a second time.
        orderExpiry.run();
        assertEquals(1L, ledgerCount("SALE_RELEASE", resv));
    }

    // ----------------------------------------------- Scenario C: paid recovery

    @Test
    void paidButUnconsumedRecoveryConsumesExactlyOnce() {
        Latte l = latte();
        OrderResponse order = checkoutHeld(l);
        settle(order.orderId(), "20");
        UUID resv = orderReservationId(order.orderId());
        assertEquals("CONSUMED", reservationStatus(resv));

        // Re-open the crash window: payment stays PAID and the order CONFIRMED, but the
        // consumption ledger and the stock draw-down are rolled back to the anomaly the
        // worker could never produce internally (settlement is atomic).
        revertConsumption(resv, l);
        assertEquals("ACTIVE", reservationStatus(resv));
        assertEquals("PAID", paymentStatus(order.orderId()));
        assertReserved(l.beans(), "18");
        assertOnHand(l.beans(), STOCK_ON_HAND);
        assertNoConsumption();

        Decision decision = recovery.recover(order.orderId(), null);

        assertEquals(Decision.CONSUMED, decision);
        assertEquals("CONSUMED", reservationStatus(resv));
        assertReserved(l.beans(), "0");
        assertOnHand(l.beans(), "982");
        assertReserved(l.cup(), "0");
        assertOnHand(l.cup(), "999");
        assertEquals(1L, ledgerCount("SALE_CONSUMPTION", resv));
        assertEquals(0L, ledgerCount("SALE_RELEASE", resv), "recovery consumes, it never releases");

        // A second recovery is a stable idempotent replay.
        assertEquals(Decision.ALREADY_CONSUMED, recovery.recover(order.orderId(), null));
        assertEquals(1L, ledgerCount("SALE_CONSUMPTION", resv));
        assertOnHand(l.beans(), "982");
    }

    // ------------------------------------------- Scenario D: ambiguous decline

    @Test
    void unsettledOrderIsNeverAutoConsumed() {
        Latte l = latte();
        OrderResponse order = checkoutHeld(l);
        UUID resv = orderReservationId(order.orderId());
        // Order advances as if confirmed, yet settlement is not provably committed.
        jdbc.update("update ordering.orders set status = 'CONFIRMED' where order_id = ?", order.orderId());

        Decision decision = recovery.recover(order.orderId(), null);

        assertEquals(Decision.MANUAL_REVIEW, decision);
        assertEquals("ACTIVE", reservationStatus(resv));
        assertReserved(l.beans(), "18"); // unchanged
        assertOnHand(l.beans(), STOCK_ON_HAND); // unchanged
        assertEquals(0L, ledgerCount("SALE_CONSUMPTION"), "declined recovery writes no consumption");
        assertEquals(0L, ledgerCount("SALE_RELEASE"), "declined recovery writes no release");
    }

    // ------------------------------------------------------------- fixtures

    /** Rewinds a settled order to the paid-but-unconsumed anomaly via raw SQL. */
    private void revertConsumption(UUID reservationId, Latte l) {
        jdbc.update("delete from inventory.stock_transaction_lines where stock_transaction_id in ("
                + "select stock_transaction_id from inventory.stock_transactions "
                + "where reservation_id = ? and transaction_type = 'SALE_CONSUMPTION')", reservationId);
        jdbc.update("delete from inventory.stock_transactions where reservation_id = ? "
                + "and transaction_type = 'SALE_CONSUMPTION'", reservationId);
        jdbc.update("update inventory.inventory_reservations set status = 'ACTIVE', consumed_at = null, "
                + "consumed_by = null where reservation_id = ?", reservationId);
        restoreBalance(l.beans(), "18");
        restoreBalance(l.milk(), "180");
        restoreBalance(l.cup(), "1");
    }

    private void restoreBalance(UUID ingredient, String held) {
        jdbc.update("update inventory.inventory_balances set current_stock = ?, reserved_stock = ? "
                + "where store_id = ? and ingredient_id = ?", new BigDecimal(STOCK_ON_HAND), dec(held), storeId,
                ingredient);
    }

    private String cartStatus(UUID cartId) {
        return jdbc.queryForObject("select status from cart.carts where cart_id = ?", String.class, cartId);
    }

    private String orderStatus(UUID orderId) {
        return jdbc.queryForObject("select status from ordering.orders where order_id = ?", String.class, orderId);
    }

    private String paymentStatus(UUID orderId) {
        return jdbc.queryForObject("select payment_status from ordering.orders where order_id = ?", String.class,
                orderId);
    }
}
