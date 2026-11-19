package com.smartcoffee.modules.promotion;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.cart.api.AddCartItemRequest;
import com.smartcoffee.modules.cart.api.CartResponse;
import com.smartcoffee.modules.cart.application.CartService;
import com.smartcoffee.modules.ordering.api.CancelOrderRequest;
import com.smartcoffee.modules.ordering.api.CreateOrderRequest;
import com.smartcoffee.modules.ordering.api.OrderResponse;
import com.smartcoffee.modules.ordering.application.OrderAdminQueryService;
import com.smartcoffee.modules.ordering.application.OrderCheckoutService;
import com.smartcoffee.modules.ordering.application.OrderCommandService;
import com.smartcoffee.modules.payment.api.CashPaymentRequest;
import com.smartcoffee.modules.payment.api.KhqrPaymentRequest;
import com.smartcoffee.modules.payment.api.PaymentResponse;
import com.smartcoffee.modules.payment.application.PaymentApplicationService;
import com.smartcoffee.modules.payment.application.PaymentExpirationService;
import com.smartcoffee.modules.payment.application.ReceiptQueryService;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Phase B2 §9/§10 lifecycle boundaries over the real services: settlement
 * redeems and can never be released, an accepted cancellation before payment
 * releases exactly once, and the authoritative payment expiry expires exactly
 * once. Every retry is asserted to be idempotent at the committed-state level.
 */
class PromotionSettlementLifecycleIT extends IntegrationTestBase {

    private static final String SUPER_ADMIN = "00000000-0000-0000-0000-000000000002";
    private static final BigDecimal PRICE = new BigDecimal("10");

    @Autowired JdbcTemplate jdbc;
    @Autowired CurrentActorProvider actors;
    @Autowired CartService carts;
    @Autowired OrderCheckoutService checkouts;
    @Autowired OrderCommandService orderCommands;
    @Autowired OrderAdminQueryService adminOrders;
    @Autowired PaymentApplicationService payments;
    @Autowired PaymentExpirationService expirations;
    @Autowired ReceiptQueryService receiptQueries;

    private CurrentActor admin;
    private UUID storeId;
    private UUID productId;

    @BeforeEach
    void setUp() {
        admin = seedAdmin();
        storeId = seedStore();
        productId = seedProduct();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        jdbc.update("update payment.payments set status='CANCELLED' where status in ('PENDING','PROCESSING') "
                + "and order_id in (select order_id from ordering.orders where store_id=?)", storeId);
    }

    @Test
    void cancellationBeforePaymentReleasesTheReservationExactlyOnce() {
        UUID promotion = promotion("CANCEL10");
        UUID user = customer();
        OrderResponse order = placed(user, "CANCEL10");
        assertEquals(1, usageCount(promotion));

        orderCommands.cancel(admin, order.orderId(), new CancelOrderRequest("customer changed mind"));

        assertEquals("RELEASED", redemptionColumn(order.orderId(), "status"));
        assertEquals("ORDER_CANCELLED", redemptionColumn(order.orderId(), "release_reason"));
        assertEquals(0, usageCount(promotion), "the unit is given back");
        assertEquals(1L, events("PromotionReleased", order.orderId()));
        assertEquals("CANCELLED", orderColumn(order.orderId(), "status"));

        // A repeated cancel is an idempotent command replay, not a second release.
        orderCommands.cancel(admin, order.orderId(), new CancelOrderRequest("customer changed mind"));
        assertEquals(0, usageCount(promotion));
        assertEquals(1L, events("PromotionReleased", order.orderId()));
        assertEquals("RELEASED", redemptionColumn(order.orderId(), "status"));
    }

    @Test
    void settledPromotionIsNeverReleased() {
        UUID promotion = promotion("FINAL10");
        UUID user = customer();
        OrderResponse order = placed(user, "FINAL10");
        payments.cash(user, UUID.randomUUID(),
                new CashPaymentRequest(order.orderId(), storeId, new BigDecimal("20"), "USD", null));
        assertEquals("REDEEMED", redemptionColumn(order.orderId(), "status"));
        assertEquals(1, usageCount(promotion));

        orderCommands.cancel(admin, order.orderId(), new CancelOrderRequest("late cancellation"));

        assertEquals("REDEEMED", redemptionColumn(order.orderId(), "status"),
                "REDEEMED is final in B2; refund/reversal is deferred");
        assertEquals(1, usageCount(promotion), "a redeemed unit is never given back as usage");
        assertEquals(0L, events("PromotionReleased", order.orderId()));
        assertEquals(0L, events("PromotionExpired", order.orderId()));
        assertNull(redemptionColumn(order.orderId(), "released_at"));
    }

    @Test
    void paymentExpiryExpiresTheReservationExactlyOnce() {
        UUID promotion = promotion("EXPIRE10");
        UUID user = customer();
        OrderResponse order = placed(user, "EXPIRE10");
        PaymentResponse khqr = payments.createKhqr(user, UUID.randomUUID(), new KhqrPaymentRequest(order.orderId()));
        assertEquals("PROCESSING", khqr.status());

        // Only this attempt's payment may be considered by the batch worker.
        jdbc.update("update payment.payments set status='CANCELLED' where status in ('PENDING','PROCESSING') "
                + "and expires_at is not null and expires_at<=now()");
        jdbc.update("update payment.payments set expires_at=? where payment_id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(120)), khqr.paymentId());

        expirations.run();

        assertEquals("EXPIRED", redemptionColumn(order.orderId(), "status"));
        assertEquals("PAYMENT_EXPIRED", redemptionColumn(order.orderId(), "release_reason"));
        assertEquals(0, usageCount(promotion), "expiry gives the unit back exactly once");
        assertEquals(1L, events("PromotionExpired", order.orderId()));
        assertEquals("CANCELLED", orderColumn(order.orderId(), "status"));
        assertEquals("FAILED", orderColumn(order.orderId(), "payment_status"));

        // Replaying the authoritative expiry is a no-op.
        expirations.run();
        assertEquals(0, usageCount(promotion));
        assertEquals(1L, events("PromotionExpired", order.orderId()));
        assertEquals("EXPIRED", redemptionColumn(order.orderId(), "status"));

        // An expired reservation can no longer be settled.
        assertThrows(ConflictException.class,
                () -> payments.cash(user, UUID.randomUUID(),
                        new CashPaymentRequest(order.orderId(), storeId, new BigDecimal("20"), "USD", null)));
        assertEquals("EXPIRED", redemptionColumn(order.orderId(), "status"));
    }

    @Test
    void orderHistoryAndReceiptKeepThePersistedSnapshotAfterTheCampaignChanges() {
        UUID promotion = promotion("SNAP10");
        UUID user = customer();
        OrderResponse order = placed(user, "SNAP10");
        payments.cash(user, UUID.randomUUID(),
                new CashPaymentRequest(order.orderId(), storeId, new BigDecimal("20"), "USD", null));

        // The campaign is edited after the sale: order history and the receipt must
        // still render exactly what was sold.
        jdbc.update("update promotion.promotions set promotion_name='Renamed Later', discount_value=50 "
                + "where promotion_id=?", promotion);

        var detail = adminOrders.detail(admin, order.orderId());
        assertNotNull(detail.promotion(), "order history exposes the persisted snapshot");
        assertEquals("SNAP10", detail.promotion().code());
        assertEquals("Promo SNAP10", detail.promotion().name());
        assertEquals(0, new BigDecimal("10").compareTo(detail.promotion().value()));
        assertEquals(0, new BigDecimal("1.0000").compareTo(detail.promotion().discountAmount()));

        var receipts = receiptQueries.receipt(admin, order.orderId());
        assertEquals(1, receipts.size());
        var snapshot = receipts.get(0).snapshot();
        assertEquals("SNAP10", snapshot.path("promotion").path("code").asString());
        assertEquals("Promo SNAP10", snapshot.path("promotion").path("name").asString());
        assertEquals("PERCENTAGE", snapshot.path("promotion").path("type").asString());
        assertEquals(0, new BigDecimal("10").compareTo(
                new BigDecimal(snapshot.path("promotion").path("value").asString())));
        assertEquals(0, new BigDecimal("1").compareTo(new BigDecimal(snapshot.path("discountAmount").asString())));
        assertEquals(0, new BigDecimal("10").compareTo(new BigDecimal(snapshot.path("subtotal").asString())));
        assertEquals(0, new BigDecimal("0.9").compareTo(new BigDecimal(snapshot.path("taxAmount").asString())));
    }

    // ------------------------------------------------------------- plumbing

    private OrderResponse placed(UUID user, String code) {
        CartResponse cart = carts.add(user, null,
                new AddCartItemRequest(storeId, productId, null, 1, null, List.of()));
        return checkouts.checkout(user, UUID.randomUUID(),
                new CreateOrderRequest(cart.cartId(), "PICKUP", null, code));
    }

    private int usageCount(UUID promotionId) {
        return jdbc.queryForObject("select usage_count from promotion.promotions where promotion_id=?",
                Integer.class, promotionId);
    }

    private String redemptionColumn(UUID orderId, String column) {
        return jdbc.queryForObject("select cast(" + column + " as text) from promotion.promotion_redemptions "
                + "where order_id=?", String.class, orderId);
    }

    private String orderColumn(UUID orderId, String column) {
        return jdbc.queryForObject("select cast(" + column + " as text) from ordering.orders where order_id=?",
                String.class, orderId);
    }

    private long events(String type, UUID orderId) {
        return jdbc.queryForObject("select count(*) from integration.outbox_events where event_type=? "
                + "and payload->'payload'->>'orderId'=?", Long.class, type, orderId.toString());
    }

    // ------------------------------------------------------------- fixtures

    private UUID promotion(String code) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into promotion.promotions (promotion_id, promotion_code, promotion_name, discount_type, "
                + "discount_value, minimum_order_amount, maximum_discount_amount, currency_code, starts_at, ends_at, "
                + "status, usage_limit, per_customer_limit, usage_count, stackable, automatic, priority, version) "
                + "values (?,?,?,'PERCENTAGE',10,0,null,'USD',?,?,'ACTIVE',null,5,0,false,false,0,0)",
                id, code, "Promo " + code, java.sql.Timestamp.from(Instant.now().minusSeconds(3600)),
                java.sql.Timestamp.from(Instant.now().plusSeconds(86400)));
        return id;
    }

    private UUID customer() {
        UUID id = UUID.randomUUID();
        String email = "promo-life-" + id + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)", id, "Promo Lifecycle Customer", email, email,
                "en-US");
        jdbc.update("insert into customer.customer_profiles (customer_id, user_id, marketing_opt_in, created_at, "
                + "updated_at, version) values (?,?,false,now(),now(),0)", UUID.randomUUID(), id);
        return id;
    }

    private CurrentActor seedAdmin() {
        UUID id = UUID.randomUUID();
        String email = "promo-life-admin-" + id + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)", id, "Promo Lifecycle Admin", email, email,
                "en-US");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, "
                + "assigned_at) values (?,?,?,?,?)", UUID.randomUUID(), id, UUID.fromString(SUPER_ADMIN), null,
                java.sql.Timestamp.from(Instant.now()));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(id.toString(), null, List.of()));
        return actors.require();
    }

    private UUID seedStore() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?, ?, 'Promo Lifecycle Branch', 'Addr', 'Asia/Phnom_Penh', 'USD', 'en-US', true, now(), now(), 0)",
                id, "PL" + UUID.randomUUID().toString().substring(0, 7).toUpperCase());
        return id;
    }

    private UUID seedProduct() {
        UUID category = jdbc.queryForObject(
                "select category_id from catalog.product_categories order by category_id limit 1", UUID.class);
        UUID id = UUID.randomUUID();
        String code = "LIFE_" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("insert into catalog.products (product_id, product_code, category_id, product_name, base_price, "
                + "currency_code, preparation_minutes, tax_rate, customizable, active, created_at, updated_at, version) "
                + "values (?,?,?,?,?, 'USD', 5, 0.1, false, true, now(), now(), 0)",
                id, code, category, "IT " + code, PRICE);
        jdbc.update("insert into catalog.store_products (store_id, product_id, available, updated_at, version) "
                + "values (?,?,true,now(),0)", storeId, id);
        return id;
    }
}
