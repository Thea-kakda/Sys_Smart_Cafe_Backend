package com.smartcoffee.modules.promotion;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.modules.cart.api.AddCartItemRequest;
import com.smartcoffee.modules.cart.api.CartResponse;
import com.smartcoffee.modules.cart.application.CartService;
import com.smartcoffee.modules.ordering.api.CreateOrderRequest;
import com.smartcoffee.modules.ordering.api.OrderResponse;
import com.smartcoffee.modules.ordering.application.OrderCheckoutService;
import com.smartcoffee.modules.payment.api.CashPaymentRequest;
import com.smartcoffee.modules.payment.api.PaymentResponse;
import com.smartcoffee.modules.payment.application.PaymentApplicationService;
import com.smartcoffee.modules.payment.application.PaymentExpirationService;
import com.smartcoffee.modules.payment.domain.Payment;
import com.smartcoffee.modules.payment.persistence.PaymentRepository;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase B2 §12 concurrency: the Promotion capacity, per-customer limit,
 * idempotent checkout, settlement replay, expiry-vs-settlement precedence and
 * the one-promotion-per-order rule are all enforced by database state, not by
 * process memory. Every scenario starts both contenders from a barrier; the
 * assertions are on committed state, so they hold for any interleaving.
 */
class PromotionUsageConcurrencyIT extends IntegrationTestBase {

    private static final BigDecimal PRICE = new BigDecimal("10");

    @Autowired JdbcTemplate jdbc;
    @Autowired CartService carts;
    @Autowired OrderCheckoutService checkouts;
    @Autowired PaymentApplicationService payments;
    @Autowired PaymentExpirationService expirations;
    @Autowired PaymentRepository paymentRows;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private UUID storeId;
    private UUID productId;
    private String productCode;

    @BeforeEach
    void setUp() {
        storeId = seedStore();
        productCode = "CONC_PROMO_" + suffix();
        productId = seedProduct(productCode);
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        jdbc.update("update payment.payments set status='CANCELLED' where status in ('PENDING','PROCESSING') "
                + "and order_id in (select order_id from ordering.orders where store_id=?)", storeId);
    }

    // ---------------------------------------------------------------- A
    @Test
    void lastGlobalUnitHasExactlyOneWinner() throws Exception {
        UUID promotion = promotion("GLOBAL1", 1, 1);
        UUID first = customer();
        UUID second = customer();
        UUID firstCart = cart(first);
        UUID secondCart = cart(second);

        List<Object> results = race(() -> checkout(first, firstCart, "GLOBAL1"),
                () -> checkout(second, secondCart, "GLOBAL1"));

        OrderResponse winner = onlyOrder(results);
        ConflictException loser = onlyConflict(results);
        assertEquals("PROMOTION_USAGE_LIMIT_REACHED", loser.code());
        assertEquals(1, usageCount(promotion), "the lost attempt must not consume a unit");
        assertEquals(1L, redemptionCount(promotion), "exactly one reservation exists");
        assertEquals("RESERVED", redemptionStatus(winner.orderId()));
        assertEquals(1L, orderCount());
        assertEquals(0, new BigDecimal("1").compareTo(orderDiscount(winner.orderId())),
                "the one order is fully discounted, never partially");
        assertEquals(1L, jdbc.queryForObject("select count(*) from cart.carts where store_id=? and status='ACTIVE'",
                Long.class, storeId), "the loser's cart stays usable");
    }

    // ---------------------------------------------------------------- B
    @Test
    void perCustomerLimitAllowsOneReservationForTheSameCustomer() throws Exception {
        UUID promotion = promotion("PERCUSTOMER1", null, 1);
        UUID otherStore = seedStore();
        UUID otherProduct = seedProduct(otherStore, "CONC_PROMO_B_" + suffix());
        UUID user = customer();
        UUID firstCart = cart(user);
        UUID secondCart = carts.add(user, null,
                new AddCartItemRequest(otherStore, otherProduct, null, 1, null, List.of())).cartId();

        List<Object> results = race(() -> checkout(user, firstCart, "PERCUSTOMER1"),
                () -> checkout(user, secondCart, "PERCUSTOMER1"));

        OrderResponse winner = onlyOrder(results);
        ConflictException loser = onlyConflict(results);
        assertEquals("PROMOTION_CUSTOMER_LIMIT_REACHED", loser.code());
        assertEquals(1, usageCount(promotion));
        assertEquals(1L, redemptionCount(promotion));
        assertEquals("RESERVED", redemptionStatus(winner.orderId()));
        assertEquals(1L, ordersIn(storeId, otherStore), "one cart became one order");
    }

    // ---------------------------------------------------------------- C
    @Test
    void oneIdempotencyKeyNeverReservesTwice() throws Exception {
        UUID promotion = promotion("SAMEKEY", null, 1);
        UUID user = customer();
        UUID cart = cart(user);
        UUID key = UUID.randomUUID();
        CreateOrderRequest request = new CreateOrderRequest(cart, "PICKUP", null, "SAMEKEY");

        List<Object> results = race(() -> checkouts.checkout(user, key, request),
                () -> checkouts.checkout(user, key, request));

        List<OrderResponse> orders = results.stream().filter(OrderResponse.class::isInstance)
                .map(OrderResponse.class::cast).toList();
        assertFalse(orders.isEmpty(), "the winning attempt must return the order: " + results);
        orders.forEach(o -> assertEquals(orders.get(0).orderId(), o.orderId()));
        for (Object result : results)
            if (result instanceof Throwable failure)
                assertTrue(failure instanceof ConflictException, "the retry must fail with a stable conflict: "
                        + failure);
        assertEquals(1L, orderCount(), "one key creates one order");
        assertEquals(1, usageCount(promotion), "one key claims one unit");
        assertEquals(1L, redemptionCount(promotion));
        assertEquals(1L, outboxCount("PromotionReserved", orders.get(0).orderId()),
                "no duplicate reservation event");
    }

    // ---------------------------------------------------------------- D
    @Test
    void settlementRedeemsOnceAndAReplayCannotRedeemAgain() throws Exception {
        UUID promotion = promotion("SETTLE10", null, 5);
        UUID user = customer();
        UUID cartId = cart(user);
        OrderResponse order = checkout(user, cartId, "SETTLE10");
        assertEquals(1, usageCount(promotion), "checkout reserves one unit");

        UUID key = UUID.randomUUID();
        CashPaymentRequest request = new CashPaymentRequest(order.orderId(), storeId, new BigDecimal("20"), "USD", null);
        PaymentResponse paid = payments.cash(user, key, request);
        assertEquals("PAID", paid.status());

        assertEquals("REDEEMED", redemptionStatus(order.orderId()));
        assertNotNull(redemptionColumn(order.orderId(), "redeemed_at"));
        assertEquals(1, usageCount(promotion), "redemption never changes usage_count");
        assertEquals(1L, outboxCount("PromotionRedeemed", order.orderId()));

        PaymentResponse replay = payments.cash(user, key, request);
        assertEquals(paid.paymentId(), replay.paymentId(), "the same key replays the settlement");
        assertThrows(ConflictException.class,
                () -> payments.cash(user, UUID.randomUUID(), request));

        assertEquals("REDEEMED", redemptionStatus(order.orderId()));
        assertEquals(1, usageCount(promotion));
        assertEquals(1L, outboxCount("PromotionRedeemed", order.orderId()), "a replay emits no second event");
        assertEquals(0L, releasedEvents(order.orderId()));
    }

    // ---------------------------------------------------------------- E
    @Test
    void expiryAndSettlementCannotBothWin() throws Exception {
        UUID promotion = promotion("RACEEXPIRY", null, 5);
        UUID user = customer();
        UUID cartId = cart(user);
        OrderResponse order = checkout(user, cartId, "RACEEXPIRY");

        // Only this attempt's payment may be considered by the batch worker.
        jdbc.update("update payment.payments set status='CANCELLED' where status in ('PENDING','PROCESSING') "
                + "and expires_at is not null and expires_at<=now()");
        UUID method = jdbc.queryForObject(
                "select payment_method_id from payment.payment_methods where method_code='KHQR'", UUID.class);
        paymentRows.save(Payment.pending(order.orderId(), storeId, method, "USD",
                order.totalAmount(), Instant.now().minusSeconds(60)));

        CashPaymentRequest request = new CashPaymentRequest(order.orderId(), storeId, new BigDecimal("20"), "USD", null);
        List<Object> results = race(() -> {
            expirations.run();
            return "expired";
        }, () -> payments.cash(user, UUID.randomUUID(), request));
        String redemption = redemptionStatus(order.orderId());
        String orderStatus = orderColumn(order.orderId(), "status");
        String paymentStatus = orderColumn(order.orderId(), "payment_status");
        if ("REDEEMED".equals(redemption)) {
            assertEquals(1, usageCount(promotion));
            assertEquals("CONFIRMED", orderStatus, "settlement won, so the order is confirmed");
            assertEquals("PAID", paymentStatus);
        } else {
            assertEquals("EXPIRED", redemption, "the losing settlement path must leave an expired reservation");
            assertEquals(0, usageCount(promotion), "expiry gives the unit back exactly once");
            assertEquals("CANCELLED", orderStatus, "expiry won, so the order is cancelled");
            assertEquals("FAILED", paymentStatus);
        }
        assertEquals(1L, redemptionCount(promotion), "never two reservations for one order");
    }

    // ---------------------------------------------------------------- F
    @Test
    void twoPromotionCodesCannotBothBeReservedForOneOrder() throws Exception {
        UUID first = promotion("TWIN_A", null, 5);
        UUID second = promotion("TWIN_B", null, 5);
        UUID user = customer();
        UUID cartId = cart(user);

        List<Object> results = race(() -> checkout(user, cartId, "TWIN_A"),
                () -> checkout(user, cartId, "TWIN_B"));

        OrderResponse winner = onlyOrder(results);
        ConflictException loser = onlyConflict(results);
        assertEquals("CART_ALREADY_CHECKED_OUT", loser.code());
        assertEquals(1L, orderCount());
        assertEquals(1L, redemptionCount(null), "exactly one promotion is reserved for the order");
        assertEquals(1, usageCount(first) + usageCount(second), "only one promotion consumed a unit");
        UUID applied = jdbc.queryForObject(
                "select promotion_id from promotion.promotion_redemptions where order_id=?", UUID.class,
                winner.orderId());
        assertEquals(1, usageCount(applied));
        UUID unused = first.equals(applied) ? second : first;
        assertEquals(0, usageCount(unused), "the other code must keep its capacity");
    }

    // ------------------------------------------------------------- plumbing

    private OrderResponse checkout(UUID user, UUID cartId, String code) {
        return checkouts.checkout(user, UUID.randomUUID(), new CreateOrderRequest(cartId, "PICKUP", null, code));
    }

    private List<Object> race(Callable<Object> one, Callable<Object> two) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        Future<Object> first = pool.submit(guarded(one, barrier));
        Future<Object> second = pool.submit(guarded(two, barrier));
        return List.of(first.get(180, TimeUnit.SECONDS), second.get(180, TimeUnit.SECONDS));
    }

    private static Callable<Object> guarded(Callable<Object> action, CyclicBarrier barrier) {
        return () -> {
            barrier.await(60, TimeUnit.SECONDS);
            try {
                return action.call();
            } catch (RuntimeException e) {
                return e;
            }
        };
    }

    private static OrderResponse onlyOrder(List<Object> results) {
        List<OrderResponse> orders = results.stream().filter(OrderResponse.class::isInstance)
                .map(OrderResponse.class::cast).toList();
        assertEquals(1, orders.size(), "exactly one attempt may commit: " + results);
        return orders.get(0);
    }

    private static ConflictException onlyConflict(List<Object> results) {
        List<ConflictException> conflicts = results.stream().filter(ConflictException.class::isInstance)
                .map(ConflictException.class::cast).toList();
        assertEquals(1, conflicts.size(), "exactly one attempt must be rejected: " + results);
        return conflicts.get(0);
    }

    private long orderCount() {
        return jdbc.queryForObject("select count(*) from ordering.orders where store_id=?", Long.class, storeId);
    }

    private BigDecimal orderDiscount(UUID orderId) {
        return jdbc.queryForObject("select promotion_discount_amount from ordering.orders where order_id=?",
                BigDecimal.class, orderId);
    }

    private int usageCount(UUID promotionId) {
        return jdbc.queryForObject("select usage_count from promotion.promotions where promotion_id=?",
                Integer.class, promotionId);
    }

    private long redemptionCount(UUID promotionId) {
        if (promotionId == null)
            return jdbc.queryForObject("select count(*) from promotion.promotion_redemptions where order_id in "
                    + "(select order_id from ordering.orders where store_id=?)", Long.class, storeId);
        return jdbc.queryForObject("select count(*) from promotion.promotion_redemptions where promotion_id=?",
                Long.class, promotionId);
    }

    private String redemptionStatus(UUID orderId) {
        return redemptionColumn(orderId, "status");
    }

    private String redemptionColumn(UUID orderId, String column) {
        return jdbc.queryForObject("select cast(" + column + " as text) from promotion.promotion_redemptions "
                + "where order_id=?", String.class, orderId);
    }

    private String orderColumn(UUID orderId, String column) {
        return jdbc.queryForObject("select cast(" + column + " as text) from ordering.orders where order_id=?",
                String.class, orderId);
    }

    private long outboxCount(String type, UUID orderId) {
        return jdbc.queryForObject("select count(*) from integration.outbox_events where event_type=? "
                + "and payload->'payload'->>'orderId'=?", Long.class, type, orderId.toString());
    }

    private long releasedEvents(UUID orderId) {
        return jdbc.queryForObject("select count(*) from integration.outbox_events where event_type in "
                + "('PromotionReleased','PromotionExpired') and payload->'payload'->>'orderId'=?", Long.class,
                orderId.toString());
    }

    private long ordersIn(UUID... stores) {
        String marks = String.join(",", java.util.Collections.nCopies(stores.length, "?"));
        return jdbc.queryForObject("select count(*) from ordering.orders where store_id in (" + marks + ")",
                Long.class, (Object[]) stores);
    }

    // ------------------------------------------------------------- fixtures

    private UUID cart(UUID user) {
        CartResponse cart = carts.add(user, null,
                new AddCartItemRequest(storeId, productId, null, 1, null, List.of()));
        return cart.cartId();
    }

    private UUID promotion(String code, Integer usageLimit, Integer perCustomer) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into promotion.promotions (promotion_id, promotion_code, promotion_name, discount_type, "
                + "discount_value, minimum_order_amount, maximum_discount_amount, currency_code, starts_at, ends_at, "
                + "status, usage_limit, per_customer_limit, usage_count, stackable, automatic, priority, version) "
                + "values (?,?,?,'PERCENTAGE',10,0,null,'USD',?,?,'ACTIVE',?,?,0,false,false,0,0)",
                id, code, "Promo " + code, java.sql.Timestamp.from(Instant.now().minusSeconds(3600)),
                java.sql.Timestamp.from(Instant.now().plusSeconds(86400)), usageLimit, perCustomer);
        return id;
    }

    private UUID customer() {
        UUID id = UUID.randomUUID();
        String email = "promo-conc-" + id + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)", id, "Promo Concurrency Customer", email, email,
                "en-US");
        jdbc.update("insert into customer.customer_profiles (customer_id, user_id, marketing_opt_in, created_at, "
                + "updated_at, version) values (?,?,false,now(),now(),0)", UUID.randomUUID(), id);
        return id;
    }

    private UUID seedStore() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?, ?, 'Promo Concurrency Branch', 'Addr', 'Asia/Phnom_Penh', 'USD', 'en-US', true, now(), now(), 0)",
                id, "PC" + UUID.randomUUID().toString().substring(0, 7).toUpperCase());
        return id;
    }

    private UUID seedProduct(String code) {
        return seedProduct(storeId, code);
    }

    private UUID seedProduct(UUID store, String code) {
        UUID category = jdbc.queryForObject(
                "select category_id from catalog.product_categories order by category_id limit 1", UUID.class);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into catalog.products (product_id, product_code, category_id, product_name, base_price, "
                + "currency_code, preparation_minutes, tax_rate, customizable, active, created_at, updated_at, version) "
                + "values (?,?,?,?,?, 'USD', 5, 0.1, false, true, now(), now(), 0)",
                id, code, category, "IT " + code, PRICE);
        jdbc.update("insert into catalog.store_products (store_id, product_id, available, updated_at, version) "
                + "values (?,?,true,now(),0)", store, id);
        return id;
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
