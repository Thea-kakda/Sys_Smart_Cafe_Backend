package com.smartcoffee.modules.payment;

import static org.junit.jupiter.api.Assertions.*;

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
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Slice 2D §16/§17-B — settlement races on one order.
 *
 * <p>Two cashiers can try to settle the same order at the same instant (two cash
 * calls), and a customer can confirm a QR payment at the same instant a cashier
 * takes the cash. In every case exactly one settlement may win: one {@code PAID}
 * payment, one confirmation, one {@code SALE_CONSUMPTION}, and one draw-down of
 * the held quantity. The order row lock in {@code confirm} plus the
 * exactly-once consumption of the reservation make the loser fail cleanly and
 * roll back its own payment row.
 */
class PaymentSettlementConcurrencyIT extends IntegrationTestBase {

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
    @Autowired RecipeCommandService recipeCommands;
    @Autowired RecipeAssignmentService assignment;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
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
        UUID product = seedProduct("RACE_" + suffix());
        offerProduct(product);
        assignRecipe(product, List.of(item(beans, "18", 0), item(milk, "180", 1), item(cup, "1", 2)));

        CartResponse cart = carts.add(userId, null,
                new AddCartItemRequest(storeId, product, null, 1, null, List.of()));
        OrderResponse order = checkouts.checkout(userId, UUID.randomUUID(),
                new CreateOrderRequest(cart.cartId(), "PICKUP", null));
        orderId = order.orderId();
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        SecurityContextHolder.clearContext();
    }

    @Test
    void twoCashSettlementsCannotBothSettleOneOrder() throws Exception {
        UUID firstKey = UUID.randomUUID();
        UUID secondKey = UUID.randomUUID();

        List<Object> results = race(() -> cash(firstKey), () -> cash(secondKey));

        assertEquals(1, results.stream().filter(r -> r instanceof PaymentResponse).count(),
                "exactly one settlement may win: " + results);
        assertSettledOnce();
    }

    @Test
    void cashAndKhqrCannotBothSettleOneOrder() throws Exception {
        // The QR payment is created (and left pending) before the race.
        PaymentResponse khqr = payments.createKhqr(userId, UUID.randomUUID(), new KhqrPaymentRequest(orderId));
        assertEquals("PROCESSING", khqr.status(), "the QR is waiting for the provider to confirm");

        List<Object> results = race(() -> cash(UUID.randomUUID()),
                () -> payments.verifyKhqr(userId, khqr.paymentId()));

        assertTrue(results.stream().anyMatch(r -> !(r instanceof RuntimeException)),
                "one of the two attempts must succeed: " + results);
        assertSettledOnce();
    }

    // ------------------------------------------------------------- assertions

    private void assertSettledOnce() {
        assertEquals(1L, jdbc.queryForObject("select count(*) from payment.payments "
                + "where order_id=? and status='PAID'", Long.class, orderId),
                "exactly one payment may be PAID");
        assertEquals("CONFIRMED", jdbc.queryForObject(
                "select status from ordering.orders where order_id=?", String.class, orderId));
        assertEquals("PAID", jdbc.queryForObject(
                "select payment_status from ordering.orders where order_id=?", String.class, orderId));

        assertEquals(1L, jdbc.queryForObject("select count(*) from inventory.stock_transactions "
                + "where store_id=? and transaction_type='SALE_CONSUMPTION'", Long.class, storeId),
                "the stock may be consumed exactly once");
        assertEquals(0, dec("0").compareTo(jdbc.queryForObject(
                "select reserved_stock from inventory.inventory_balances where store_id=? and ingredient_id=?",
                BigDecimal.class, storeId, beans)), "the whole hold was consumed, not part of it");
        assertEquals(0, dec("982").compareTo(jdbc.queryForObject(
                "select current_stock from inventory.inventory_balances where store_id=? and ingredient_id=?",
                BigDecimal.class, storeId, beans)), "on-hand fell by exactly one order's beans");
        assertEquals("CONSUMED", jdbc.queryForObject("select cast(status as text) from "
                + "inventory.inventory_reservations where order_id=?", String.class, orderId));
        assertEquals(0L, jdbc.queryForObject("select count(*) from inventory.stock_transactions "
                + "where store_id=? and transaction_type='SALE_RELEASE'", Long.class, storeId),
                "a settlement race must never release");
    }

    // ------------------------------------------------------------- plumbing

    private PaymentResponse cash(UUID key) {
        return payments.cash(userId, key,
                new CashPaymentRequest(orderId, storeId, dec("20"), "USD", null));
    }

    private List<Object> race(Callable<Object> one, Callable<Object> two) throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        Future<Object> first = pool.submit(guarded(one, barrier));
        Future<Object> second = pool.submit(guarded(two, barrier));
        return List.of(first.get(120, TimeUnit.SECONDS), second.get(120, TimeUnit.SECONDS));
    }

    /** Starts both threads at the same instant and converts a business failure into a value. */
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

    // ------------------------------------------------------------- fixtures

    private UUID assignRecipe(UUID productId, List<RecipeItemRequest> items) {
        RecipeVersionResponse draft = recipeCommands.createDraft(admin, productId,
                new CreateRecipeDraftRequest(BigDecimal.ONE, null, "race " + suffix(), items));
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
                + "values (?, ?, 'Settlement Race Branch', 'Addr', 'Asia/Phnom_Penh', 'USD', 'en-US', true, now(), now(), 0)",
                id, "SR" + UUID.randomUUID().toString().substring(0, 7).toUpperCase());
        return id;
    }

    private UUID seedCustomer() {
        UUID id = UUID.randomUUID();
        String email = "settlement-race-" + id + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)", id, "Settlement Race Customer", email, email,
                "en-US");
        jdbc.update("insert into customer.customer_profiles (customer_id, user_id, marketing_opt_in, created_at, "
                + "updated_at, version) values (?,?,false,now(),now(),0)", UUID.randomUUID(), id);
        return id;
    }

    private CurrentActor seedActor(String roleId) {
        UUID id = UUID.randomUUID();
        String email = "settlement-race-admin-" + id + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)", id, "Settlement Race Admin", email, email, "en-US");
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
