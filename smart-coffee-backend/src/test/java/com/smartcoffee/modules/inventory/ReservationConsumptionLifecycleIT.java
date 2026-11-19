package com.smartcoffee.modules.inventory;

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
import com.smartcoffee.modules.ordering.application.OrderCheckoutService;
import com.smartcoffee.modules.ordering.application.OrderCommandService;
import com.smartcoffee.modules.payment.api.CashPaymentRequest;
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
 * Slice 2D §10/§14/§15/§22 — the two terminal transitions of an order
 * reservation, end to end:
 *
 * <ul>
 *   <li><b>Settlement consumes</b>: the payment that confirms the order draws the
 *       converted snapshot down once. {@code current_stock} and
 *       {@code reserved_stock} fall by the same quantity, exactly one
 *       {@code SALE_CONSUMPTION} row is written, and a second settlement attempt
 *       can never draw again.</li>
 *   <li><b>Cancellation releases</b>: before settlement the hold is given back
 *       once with {@code ORDER_CANCELLED}; repeated cancellation is a no-op; and
 *       once consumed, cancellation can never give the stock back.</li>
 * </ul>
 *
 * <p>Every path also asserts the §21 no-double-movement invariant: no
 * {@code SALE_RELEASE} + {@code SALE_CONSUMPTION} pair for the same quantity, and
 * no consumption before settlement.
 */
class ReservationConsumptionLifecycleIT extends IntegrationTestBase {

    private static final String SUPER_ADMIN = "00000000-0000-0000-0000-000000000002";
    private static final UUID GRAM = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final UUID MILLILITER = UUID.fromString("60000000-0000-0000-0000-000000000003");
    private static final UUID PIECE = UUID.fromString("60000000-0000-0000-0000-000000000005");
    private static final String STOCK_ON_HAND = "1000";

    @Autowired JdbcTemplate jdbc;
    @Autowired CurrentActorProvider actors;
    @Autowired CartService carts;
    @Autowired OrderCheckoutService checkouts;
    @Autowired OrderCommandService commands;
    @Autowired PaymentApplicationService payments;
    @Autowired RecipeCommandService recipeCommands;
    @Autowired RecipeAssignmentService assignment;

    private CurrentActor admin;
    private UUID storeId;
    private UUID userId;

    @BeforeEach
    void setUp() {
        admin = seedActor(SUPER_ADMIN);
        storeId = seedStore();
        userId = seedCustomer();
    }

    @AfterEach
    void clearActor() {
        SecurityContextHolder.clearContext();
    }

    // --------------------------------------------- settlement consumes once

    @Test
    void settlementConsumesTheConvertedSnapshotExactlyOnce() {
        Latte l = latte();
        OrderResponse order = checkoutAndAssertHeld(l);
        assertNoConsumption();

        settle(order.orderId(), "20");

        // The held quantity left inventory: current and reserved fall together.
        assertOnHand(l.beans, "982");
        assertReserved(l.beans, "0");
        assertOnHand(l.cup, "999");
        assertReserved(l.cup, "0");

        assertEquals("CONFIRMED", jdbc.queryForObject(
                "select status from ordering.orders where order_id=?", String.class, order.orderId()));
        assertEquals("PAID", jdbc.queryForObject(
                "select payment_status from ordering.orders where order_id=?", String.class, order.orderId()));

        UUID reservation = orderReservationId(order.orderId());
        assertEquals("CONSUMED", reservationColumn(reservation, "status"));
        assertNotNull(reservationColumn(reservation, "consumed_at"));
        assertEquals(userId.toString(), reservationColumn(reservation, "consumed_by"),
                "settlement is attributed to the paying customer");

        // Exactly one consumption, carrying the cart provenance and the converted
        // snapshot quantities.
        assertEquals(1L, consumptionHeaders());
        assertEquals("ORDER_SETTLED", consumptionReason());
        assertEquals(reservation.toString(), jdbc.queryForObject(
                "select cast(reservation_id as text) from inventory.stock_transactions "
                        + "where store_id=? and transaction_type='SALE_CONSUMPTION'", String.class, storeId));
        assertEquals(6L, consumptionLines());
        assertEquals(0, dec("-18").compareTo(consumptionDelta(l.beans)),
                "the consumed quantity is the snapshot quantity");
        assertEquals(0, ledgerHeaders("SALE_RELEASE"), "settlement never releases");

        // A second settlement cannot draw again.
        ConflictException conflict = assertThrows(ConflictException.class, () -> settle(order.orderId(), "20"));
        assertEquals("ORDER_ALREADY_PAID", conflict.code());
        assertEquals(1L, consumptionHeaders());
        assertOnHand(l.beans, "982");
        assertReserved(l.beans, "0");
    }

    // -------------------------------------------- cancellation releases once

    @Test
    void cancellationBeforeSettlementReleasesTheHoldExactlyOnce() {
        Latte l = latte();
        OrderResponse order = checkoutAndAssertHeld(l);
        UUID reservation = orderReservationId(order.orderId());

        commands.cancel(admin, order.orderId(), new CancelOrderRequest("customer changed mind"));

        // Only the hold moves back; on-hand stock is untouched.
        assertReserved(l.beans, "0");
        assertReserved(l.milk, "0");
        assertOnHand(l.beans, STOCK_ON_HAND);
        assertEquals("RELEASED", reservationColumn(reservation, "status"));
        assertEquals("ORDER_CANCELLED", reservationColumn(reservation, "release_reason"));
        assertEquals(1L, ledgerHeaders("SALE_RELEASE"));
        assertEquals("ORDER_CANCELLED", releaseReason());
        assertEquals(1L, releaseHeadersForReservation(reservation));
        assertNoConsumption();
        assertEquals("CANCELLED", jdbc.queryForObject(
                "select status from ordering.orders where order_id=?", String.class, order.orderId()));

        // Replaying the command is idempotent and cannot release twice.
        commands.cancel(admin, order.orderId(), new CancelOrderRequest("customer changed mind"));
        assertEquals(1L, ledgerHeaders("SALE_RELEASE"));
        assertReserved(l.beans, "0");

        // A cancelled order can no longer be settled.
        assertThrows(ConflictException.class, () -> settle(order.orderId(), "20"));
        assertNoConsumption();
        assertReserved(l.beans, "0");
    }

    @Test
    void aCancellationAfterSettlementCannotGiveConsumedStockBack() {
        Latte l = latte();
        OrderResponse order = checkoutAndAssertHeld(l);
        UUID reservation = orderReservationId(order.orderId());
        settle(order.orderId(), "20");
        assertConsumed(l, order, reservation);

        commands.cancel(admin, order.orderId(), new CancelOrderRequest("goodwill cancellation after handover"));

        // The order is cancelled administratively, but consumed stock stays consumed.
        assertEquals("CANCELLED", jdbc.queryForObject(
                "select status from ordering.orders where order_id=?", String.class, order.orderId()));
        assertEquals("CONSUMED", reservationColumn(reservation, "status"));
        assertEquals(0, ledgerHeaders("SALE_RELEASE"), "consumed stock can never be released");
        assertOnHand(l.beans, "982");
        assertReserved(l.beans, "0");
        assertEquals(1L, consumptionHeaders());
    }

    @Test
    void settlementAndCancellationRaceLeavesOneTerminalOutcome() throws Exception {
        Latte l = latte();
        OrderResponse order = checkoutAndAssertHeld(l);
        UUID reservation = orderReservationId(order.orderId());

        ExecutorService pool = Executors.newFixedThreadPool(2);
        List<Object> results;
        try {
            CyclicBarrier barrier = new CyclicBarrier(2);
            Future<Object> settled = pool.submit(guarded(() -> {
                settle(order.orderId(), "20");
                return "settled";
            }, barrier));
            Future<Object> cancelled = pool.submit(guarded(() -> commands.cancel(admin, order.orderId(),
                    new CancelOrderRequest("customer cancels while paying")), barrier));
            results = List.of(settled.get(120, TimeUnit.SECONDS), cancelled.get(120, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }

        // Both paths lock the order row before the reservation, so the loser observes
        // the winner's committed state instead of interleaving with it.
        long consumed = consumptionHeaders();
        long released = ledgerHeaders("SALE_RELEASE");
        assertEquals(1L, consumed + released,
                "exactly one terminal ledger row may exist, but consumption=" + consumed + " release=" + released
                        + " results=" + results);
        assertEquals(0, dec("0").compareTo(reserved(l.beans)), "the hold is always resolved");
        if (consumed == 1L) {
            assertEquals("CONSUMED", reservationColumn(reservation, "status"));
            assertOnHand(l.beans, "982");
        } else {
            assertEquals("RELEASED", reservationColumn(reservation, "status"));
            assertEquals("ORDER_CANCELLED", reservationColumn(reservation, "release_reason"));
            assertOnHand(l.beans, STOCK_ON_HAND);
        }
    }

    // ------------------------------------------------------------- fixtures

    private record Latte(UUID productId, UUID beans, UUID milk, UUID syrup, UUID ice, UUID cup, UUID lid) {
    }

    private Latte latte() {
        UUID productId = seedProduct("LATTE_" + suffix());
        offerProduct(storeId, productId);
        UUID beans = seedIngredient("BEANS_" + suffix(), GRAM);
        UUID milk = seedIngredient("MILK_" + suffix(), MILLILITER);
        UUID syrup = seedIngredient("SYRUP_" + suffix(), MILLILITER);
        UUID ice = seedIngredient("ICE_" + suffix(), GRAM);
        UUID cup = seedIngredient("CUP12_" + suffix(), PIECE);
        UUID lid = seedIngredient("LID12_" + suffix(), PIECE);
        for (UUID ingredient : List.of(beans, milk, syrup, ice, cup, lid)) {
            seedBalance(ingredient, STOCK_ON_HAND);
        }
        assignRecipe(productId, "latte 12oz", List.of(
                item(beans, "18", 0), item(milk, "180", 1), item(syrup, "20", 2),
                item(ice, "120", 3), item(cup, "1", 4), item(lid, "1", 5)));
        return new Latte(productId, beans, milk, syrup, ice, cup, lid);
    }

    /** Adds one latte to a fresh cart and checks it out; asserts nothing is consumed yet. */
    private OrderResponse checkoutAndAssertHeld(Latte l) {
        CartResponse cart = carts.add(userId, null,
                new AddCartItemRequest(storeId, l.productId(), null, 1, null, List.of()));
        OrderResponse order = checkouts.checkout(userId, UUID.randomUUID(),
                new CreateOrderRequest(cart.cartId(), "PICKUP", null));
        assertEquals("ACTIVE", reservationColumn(orderReservationId(order.orderId()), "status"));
        assertReserved(l.beans, "18");
        assertOnHand(l.beans, STOCK_ON_HAND);
        assertNoConsumption();
        return order;
    }

    private void settle(UUID orderId, String received) {
        payments.cash(userId, UUID.randomUUID(),
                new CashPaymentRequest(orderId, storeId, dec(received), "USD", null));
    }

    /** Starts both threads together and turns a business failure into a value. */
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

    private void assertConsumed(Latte l, OrderResponse order, UUID reservation) {
        assertEquals("CONSUMED", reservationColumn(reservation, "status"));
        assertEquals(1L, consumptionHeaders());
        assertEquals(0, dec("-18").compareTo(consumptionDelta(l.beans)));
        assertOnHand(l.beans, "982");
        assertReserved(l.beans, "0");
        assertEquals("CONFIRMED", jdbc.queryForObject(
                "select status from ordering.orders where order_id=?", String.class, order.orderId()));
    }

    // ------------------------------------------------------------ assertions

    private void assertReserved(UUID ingredient, String expected) {
        assertEquals(0, dec(expected).compareTo(reserved(ingredient)),
                "reserved_stock for " + ingredient + " (expected " + expected + ")");
    }

    private void assertOnHand(UUID ingredient, String expected) {
        assertEquals(0, dec(expected).compareTo(jdbc.queryForObject(
                "select current_stock from inventory.inventory_balances where store_id=? and ingredient_id=?",
                BigDecimal.class, storeId, ingredient)),
                "current_stock for " + ingredient + " (expected " + expected + ")");
    }

    private void assertNoConsumption() {
        assertEquals(0L, consumptionHeaders(), "no SALE_CONSUMPTION row may exist for this store");
    }

    private BigDecimal reserved(UUID ingredient) {
        return jdbc.queryForObject("select reserved_stock from inventory.inventory_balances "
                + "where store_id=? and ingredient_id=?", BigDecimal.class, storeId, ingredient);
    }

    private long consumptionHeaders() {
        return jdbc.queryForObject("select count(*) from inventory.stock_transactions "
                + "where store_id=? and transaction_type='SALE_CONSUMPTION'", Long.class, storeId);
    }

    private long consumptionLines() {
        return jdbc.queryForObject("select count(*) from inventory.stock_transaction_lines l "
                + "join inventory.stock_transactions t on t.stock_transaction_id=l.stock_transaction_id "
                + "where t.store_id=? and t.transaction_type='SALE_CONSUMPTION'", Long.class, storeId);
    }

    private String consumptionReason() {
        return jdbc.queryForObject("select reason from inventory.stock_transactions "
                + "where store_id=? and transaction_type='SALE_CONSUMPTION'", String.class, storeId);
    }

    private String releaseReason() {
        return jdbc.queryForObject("select reason from inventory.stock_transactions "
                + "where store_id=? and transaction_type='SALE_RELEASE'", String.class, storeId);
    }

    private BigDecimal consumptionDelta(UUID ingredient) {
        return jdbc.queryForObject("select coalesce(sum(l.quantity_delta),0) from "
                + "inventory.stock_transaction_lines l join inventory.stock_transactions t "
                + "on t.stock_transaction_id=l.stock_transaction_id where t.store_id=? "
                + "and t.transaction_type='SALE_CONSUMPTION' and l.ingredient_id=?", BigDecimal.class, storeId,
                ingredient);
    }

    private long ledgerHeaders(String type) {
        return jdbc.queryForObject("select count(*) from inventory.stock_transactions "
                + "where store_id=? and transaction_type=?", Long.class, storeId, type);
    }

    private long releaseHeadersForReservation(UUID reservation) {
        return jdbc.queryForObject("select count(*) from inventory.stock_transactions "
                + "where reservation_id=? and transaction_type='SALE_RELEASE'", Long.class, reservation);
    }

    private UUID orderReservationId(UUID orderId) {
        return jdbc.queryForObject("select reservation_id from inventory.inventory_reservations where order_id=?",
                UUID.class, orderId);
    }

    private String reservationColumn(UUID reservationId, String column) {
        return jdbc.queryForObject("select cast(" + column + " as text) from inventory.inventory_reservations "
                + "where reservation_id=?", String.class, reservationId);
    }

    // --------------------------------------------------------- raw seeding

    private UUID assignRecipe(UUID productId, String name, List<RecipeItemRequest> items) {
        RecipeVersionResponse draft = recipeCommands.createDraft(admin, productId,
                new CreateRecipeDraftRequest(BigDecimal.ONE, null, name, items));
        RecipeVersionResponse active = recipeCommands.activate(admin, draft.recipeVersionId(), draft.version());
        assignment.assignProduct(admin, productId, new AssignProductRecipeRequest(active.recipeVersionId(),
                jdbc.queryForObject("select version from catalog.products where product_id=?", Long.class,
                        productId)));
        return active.recipeVersionId();
    }

    private void offerProduct(UUID store, UUID productId) {
        jdbc.update("insert into catalog.store_products (store_id, product_id, available, updated_at, version) "
                + "values (?,?,true,now(),0)", store, productId);
    }

    private UUID seedStore() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?, ?, 'Settlement Branch', 'Addr', 'Asia/Phnom_Penh', 'USD', 'en-US', true, now(), now(), 0)",
                id, "SET" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        return id;
    }

    private UUID seedCustomer() {
        UUID id = UUID.randomUUID();
        String email = "settle-it-" + id + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)", id, "Settlement IT Customer", email, email, "en-US");
        jdbc.update("insert into customer.customer_profiles (customer_id, user_id, marketing_opt_in, created_at, "
                + "updated_at, version) values (?,?,false,now(),now(),0)", UUID.randomUUID(), id);
        return id;
    }

    private CurrentActor seedActor(String roleId) {
        UUID id = UUID.randomUUID();
        String email = "settle-it-admin-" + id + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)", id, "Settlement IT Admin", email, email, "en-US");
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
                + "values (?,?,?,?,?, 'USD', 5, 0.1, true, true, now(), now(), 0)",
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
