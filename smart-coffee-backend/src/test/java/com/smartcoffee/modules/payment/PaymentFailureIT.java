package com.smartcoffee.modules.payment;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.cart.api.AddCartItemRequest;
import com.smartcoffee.modules.cart.api.CartResponse;
import com.smartcoffee.modules.cart.application.CartService;
import com.smartcoffee.modules.ordering.api.CreateOrderRequest;
import com.smartcoffee.modules.ordering.api.OrderResponse;
import com.smartcoffee.modules.ordering.application.OrderCheckoutService;
import com.smartcoffee.modules.payment.api.CashPaymentRequest;
import com.smartcoffee.modules.payment.api.KhqrPaymentRequest;
import com.smartcoffee.modules.payment.api.PaymentResponse;
import com.smartcoffee.modules.payment.application.PaymentApplicationService;
import com.smartcoffee.modules.payment.application.PaymentExpirationService;
import com.smartcoffee.modules.recipe.api.RecipeVersionResponse;
import com.smartcoffee.modules.recipe.api.request.AssignProductRecipeRequest;
import com.smartcoffee.modules.recipe.api.request.CreateRecipeDraftRequest;
import com.smartcoffee.modules.recipe.api.request.RecipeItemRequest;
import com.smartcoffee.modules.recipe.application.RecipeAssignmentService;
import com.smartcoffee.modules.recipe.application.RecipeCommandService;
import com.smartcoffee.modules.recipe.domain.RecipeScalingRule;
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
 * Slice 2D §15 — a payment failure never touches stock, and the abandoned window
 * releases the order hold exactly once.
 *
 * <ol>
 *   <li>An unsuccessful settlement (insufficient tender) leaves the order
 *       {@code AWAITING_PAYMENT}/{@code PENDING} and its reservation
 *       {@code ACTIVE}. Nothing is consumed and no stock moves.</li>
 *   <li>When the payment window expires, the order is cancelled and the hold is
 *       released with {@code PAYMENT_FAILED}: {@code reserved_stock} falls,
 *       {@code current_stock} does not, and exactly one {@code SALE_RELEASE} row
 *       is written.</li>
 *   <li>The expiry job is idempotent: running it again moves nothing, and the
 *       cancelled order can no longer be settled.</li>
 * </ol>
 */
class PaymentFailureIT extends IntegrationTestBase {

    private static final String SUPER_ADMIN = "00000000-0000-0000-0000-000000000002";
    private static final UUID GRAM = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final UUID MILLILITER = UUID.fromString("60000000-0000-0000-0000-000000000003");
    private static final UUID PIECE = UUID.fromString("60000000-0000-0000-0000-000000000005");
    private static final String STOCK_ON_HAND = "1000";

    @Autowired JdbcTemplate jdbc;
    @Autowired CurrentActorProvider actors;
    @Autowired CartService carts;
    @Autowired OrderCheckoutService checkouts;
    @Autowired PaymentApplicationService payments;
    @Autowired PaymentExpirationService expirations;
    @Autowired RecipeCommandService recipeCommands;
    @Autowired RecipeAssignmentService assignment;

    private CurrentActor admin;
    private UUID storeId;
    private UUID userId;
    private UUID beans;
    private UUID orderId;

    @BeforeEach
    void setUp() {
        admin = seedActor(SUPER_ADMIN);
        storeId = seedStore();
        userId = seedCustomer();
        beans = seedIngredient("BEANS_" + suffix(), GRAM);
        UUID milk = seedIngredient("MILK_" + suffix(), MILLILITER);
        UUID cup = seedIngredient("CUP12_" + suffix(), PIECE);
        for (UUID ingredient : List.of(beans, milk, cup)) {
            seedBalance(ingredient, STOCK_ON_HAND);
        }
        UUID product = seedProduct("FAIL_" + suffix());
        offerProduct(product);
        assignRecipe(product, List.of(item(beans, "18", 0), item(milk, "180", 1), item(cup, "1", 2)));

        CartResponse cart = carts.add(userId, null,
                new AddCartItemRequest(storeId, product, null, 1, null, List.of()));
        OrderResponse order = checkouts.checkout(userId, UUID.randomUUID(),
                new CreateOrderRequest(cart.cartId(), "PICKUP", null));
        orderId = order.orderId();
    }

    @AfterEach
    void clearActor() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void failedAttemptKeepsOrderAwaitingPaymentAndReservationActive() {
        // 5.00 tendered for an 11.00 order: the settlement is refused outright.
        ConflictException insufficient = assertThrows(ConflictException.class,
                () -> cash("5"));
        assertEquals("INSUFFICIENT_CASH_RECEIVED", insufficient.code());

        assertEquals("AWAITING_PAYMENT", orderColumn("status"));
        assertEquals("PENDING", orderColumn("payment_status"));
        assertEquals("ACTIVE", reservationColumn("status"));
        assertReserved("18");
        assertOnHand();
        assertEquals(0L, consumptionHeaders(), "a failed attempt must not consume");
        assertEquals(0L, releaseHeaders(), "a failed attempt must not release");

        // The customer starts a KHQR payment and abandons it.
        PaymentResponse khqr = payments.createKhqr(userId, UUID.randomUUID(), new KhqrPaymentRequest(orderId));
        assertEquals("PROCESSING", khqr.status(), "the QR is waiting for the provider to confirm");
        jdbc.update("update payment.payments set expires_at=? where payment_id=?",
                java.sql.Timestamp.from(Instant.now().minusSeconds(120)), khqr.paymentId());

        expirations.run();

        assertEquals("EXPIRED", jdbc.queryForObject(
                "select status from payment.payments where payment_id=?", String.class, khqr.paymentId()));
        assertEquals("CANCELLED", orderColumn("status"));
        assertEquals("FAILED", orderColumn("payment_status"));

        // The hold is given back once, and only the hold.
        assertEquals("RELEASED", reservationColumn("status"));
        assertEquals("PAYMENT_FAILED", reservationColumn("release_reason"));
        assertReserved("0");
        assertOnHand();
        assertEquals(1L, releaseHeaders());
        assertEquals("PAYMENT_FAILED", jdbc.queryForObject("select reason from inventory.stock_transactions "
                + "where store_id=? and transaction_type='SALE_RELEASE'", String.class, storeId));
        assertEquals(0L, consumptionHeaders());

        // Replaying the expiry job is a no-op.
        expirations.run();
        assertEquals(1L, releaseHeaders());
        assertReserved("0");
        assertOnHand();

        // A cancelled order can no longer be settled.
        assertThrows(ConflictException.class, () -> cash("20"));
        assertEquals(0L, consumptionHeaders());
        assertOnHand();
    }

    // ------------------------------------------------------------- fixtures

    private void cash(String received) {
        payments.cash(userId, UUID.randomUUID(),
                new CashPaymentRequest(orderId, storeId, dec(received), "USD", null));
    }

    private void assertReserved(String expected) {
        assertEquals(0, dec(expected).compareTo(jdbc.queryForObject(
                "select reserved_stock from inventory.inventory_balances where store_id=? and ingredient_id=?",
                BigDecimal.class, storeId, beans)), "reserved_stock (expected " + expected + ")");
    }

    private void assertOnHand() {
        assertEquals(0, dec(STOCK_ON_HAND).compareTo(jdbc.queryForObject(
                "select current_stock from inventory.inventory_balances where store_id=? and ingredient_id=?",
                BigDecimal.class, storeId, beans)), "current_stock must never move before a settlement");
    }

    private long consumptionHeaders() {
        return jdbc.queryForObject("select count(*) from inventory.stock_transactions "
                + "where store_id=? and transaction_type='SALE_CONSUMPTION'", Long.class, storeId);
    }

    private long releaseHeaders() {
        return jdbc.queryForObject("select count(*) from inventory.stock_transactions "
                + "where store_id=? and transaction_type='SALE_RELEASE'", Long.class, storeId);
    }

    private String orderColumn(String column) {
        return jdbc.queryForObject("select cast(" + column + " as text) from ordering.orders where order_id=?",
                String.class, orderId);
    }

    private String reservationColumn(String column) {
        return jdbc.queryForObject("select cast(" + column + " as text) from inventory.inventory_reservations "
                + "where order_id=?", String.class, orderId);
    }

    private UUID assignRecipe(UUID productId, List<RecipeItemRequest> items) {
        RecipeVersionResponse draft = recipeCommands.createDraft(admin, productId,
                new CreateRecipeDraftRequest(BigDecimal.ONE, null, "failure " + suffix(), items));
        RecipeVersionResponse active = recipeCommands.activate(admin, draft.recipeVersionId(), draft.version());
        assignment.assignProduct(admin, productId, new AssignProductRecipeRequest(active.recipeVersionId(),
                jdbc.queryForObject("select version from catalog.products where product_id=?", Long.class,
                        productId)));
        return active.recipeVersionId();
    }

    private void offerProduct(UUID productId) {
        jdbc.update("insert into catalog.store_products (store_id, product_id, available, updated_at, version) "
                + "values (?,?,true,now(),0)", storeId, productId);
    }

    private UUID seedStore() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?, ?, 'Payment Failure Branch', 'Addr', 'Asia/Phnom_Penh', 'USD', 'en-US', true, now(), now(), 0)",
                id, "PF" + UUID.randomUUID().toString().substring(0, 7).toUpperCase());
        return id;
    }

    private UUID seedCustomer() {
        UUID id = UUID.randomUUID();
        String email = "payment-failure-" + id + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)", id, "Payment Failure Customer", email, email,
                "en-US");
        jdbc.update("insert into customer.customer_profiles (customer_id, user_id, marketing_opt_in, created_at, "
                + "updated_at, version) values (?,?,false,now(),now(),0)", UUID.randomUUID(), id);
        return id;
    }

    private CurrentActor seedActor(String roleId) {
        UUID id = UUID.randomUUID();
        String email = "payment-failure-admin-" + id + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)", id, "Payment Failure Admin", email, email, "en-US");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, "
                + "assigned_at) values (?,?,?,?,?)", UUID.randomUUID(), id, UUID.fromString(roleId), null,
                java.sql.Timestamp.from(Instant.now()));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(id.toString(), null, List.of()));
        return actors.require();
    }

    private UUID seedProduct(String code) {
        UUID category = jdbc.queryForObject(
                "select category_id from catalog.product_categories order by category_id limit 1", UUID.class);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into catalog.products (product_id, product_code, category_id, product_name, base_price, "
                + "currency_code, preparation_minutes, tax_rate, customizable, active, created_at, updated_at, version) "
                + "values (?,?,?,?,?, 'USD', 5, 0.1, false, true, now(), now(), 0)",
                id, code, category, "IT " + code, dec("10"));
        return id;
    }

    private UUID seedIngredient(String code, UUID baseUnit) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into inventory.ingredients (ingredient_id, ingredient_code, ingredient_name, "
                + "base_unit_id, standard_cost, track_inventory, track_batches, active, created_at, updated_at, "
                + "version) values (?,?,?,?,0,true,false,true,now(),now(),0)", id, code, code, baseUnit);
        return id;
    }

    private void seedBalance(UUID ingredient, String current) {
        jdbc.update("insert into inventory.inventory_balances (store_id, ingredient_id, current_stock, "
                + "reserved_stock, minimum_stock, average_unit_cost) values (?,?,?,0,0,0.025)", storeId, ingredient,
                dec(current));
    }

    private static RecipeItemRequest item(UUID ingredient, String qty, int order) {
        return new RecipeItemRequest(ingredient, dec(qty), BigDecimal.ZERO, null, RecipeScalingRule.FIXED, order,
                false, null);
    }

    private static BigDecimal dec(String value) {
        return new BigDecimal(value);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
