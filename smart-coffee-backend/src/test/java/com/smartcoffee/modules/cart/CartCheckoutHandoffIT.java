package com.smartcoffee.modules.cart;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.cart.api.AddCartItemRequest;
import com.smartcoffee.modules.cart.api.CartItemResponse;
import com.smartcoffee.modules.cart.api.CartResponse;
import com.smartcoffee.modules.cart.application.CartService;
import com.smartcoffee.modules.ordering.api.CreateOrderRequest;
import com.smartcoffee.modules.ordering.api.OrderResponse;
import com.smartcoffee.modules.ordering.application.OrderCheckoutService;
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
 * Slice 2D §4/§5/§6/§9 — the checkout handoff is now an ownership
 * <b>conversion</b>, not a release plus re-reservation.
 *
 * <p>Four things must be proven:
 *
 * <ol>
 *   <li><b>Metadata-only transfer</b>: the cart row becomes {@code CONVERTED} and
 *       points at the one order reservation that received its demand. No
 *       {@code SALE_RELEASE} and no second {@code SALE_RESERVATION} is written,
 *       and neither {@code reserved_stock} nor {@code current_stock} moves — so
 *       there is no committed instant in which the stock is briefly free.</li>
 *   <li><b>Snapshot authority</b>: the order holds what the cart persisted. A
 *       recipe edited after the line was added cannot change the order's
 *       demand.</li>
 *   <li><b>Legacy line</b>: a line that never held stock (its stock mode plans no
 *       demand) is calculated once at checkout and reserved for the order in the
 *       same transaction, with its own audited ledger row.</li>
 *   <li><b>Atomicity</b>: when that legacy reservation fails, everything rolls
 *       back — the cart keeps its {@code ACTIVE} hold and a retry with the same
 *       idempotency key succeeds.</li>
 * </ol>
 */
class CartCheckoutHandoffIT extends IntegrationTestBase {

    private static final String SUPER_ADMIN = "00000000-0000-0000-0000-000000000002";
    private static final UUID GRAM = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final UUID MILLILITER = UUID.fromString("60000000-0000-0000-0000-000000000003");
    private static final UUID PIECE = UUID.fromString("60000000-0000-0000-0000-000000000005");
    private static final String STOCK_ON_HAND = "1000";

    @Autowired JdbcTemplate jdbc;
    @Autowired CurrentActorProvider actors;
    @Autowired CartService carts;
    @Autowired OrderCheckoutService checkouts;
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

    // ------------------------------------------------ committed conversion

    @Test
    void checkoutConvertsTheCartHoldIntoTheOrderHold() {
        Latte l = latte();
        CartResponse cart = add(l, 1);
        UUID itemId = onlyItem(cart).cartItemId();
        UUID cartReservation = reservationId(itemId);
        assertReserved(l.beans, "18");
        assertReserved(l.cup, "1");

        OrderResponse order = checkouts.checkout(userId, UUID.randomUUID(),
                new CreateOrderRequest(cart.cartId(), "PICKUP", null));

        // The cart-owned row is CONVERTED (not released) and keeps its snapshot.
        assertEquals("CONVERTED", reservationColumn(itemId, "status"));
        assertNotNull(reservationColumn(itemId, "converted_at"));
        assertEquals(order.orderId().toString(), reservationColumn(itemId, "converted_order_id"));
        assertNull(reservationColumn(itemId, "release_reason"), "a conversion is not a release");
        assertNull(reservationColumn(itemId, "released_at"));
        assertNull(reservationColumn(itemId, "consumed_at"));
        assertQuantity(cartReservation, l.beans, "18",
                "the cart row keeps the snapshot it held for audit");

        // A separate order-owned row carries the same demand and names the cart as
        // provenance.
        UUID orderReservation = orderReservationId(order.orderId());
        assertNotEquals(cartReservation, orderReservation, "the order hold is its own reservation");
        assertEquals(orderReservation.toString(), reservationColumn(itemId, "converted_to_reservation_id"));
        assertEquals("ACTIVE", orderReservationColumn(order.orderId(), "status"));
        assertEquals("ORDER", orderReservationColumn(order.orderId(), "source_type"));
        assertNull(orderReservationColumn(order.orderId(), "cart_item_id"));
        assertEquals(cart.cartId().toString(), orderReservationColumn(order.orderId(), "cart_id"),
                "the cart stays as provenance on the order hold");
        assertEquals(6L, orderReservationLineCount(order.orderId()));
        assertQuantity(orderReservation, l.beans, "18", "the order hold carries the cart snapshot");

        // Every converted cart line names the order line that snapshotted it.
        UUID orderLine = UUID.fromString(reservationColumn(itemId, "converted_order_line_id"));
        assertEquals(itemId, jdbc.queryForObject(
                "select cart_item_id from ordering.order_items where order_item_id=?", UUID.class, orderLine),
                "the order line records the cart line it came from");

        // Net reserved quantity is untouched: transferred, never released to the
        // pool, never doubled, and on-hand stock never moves.
        assertReserved(l.beans, "18");
        assertReserved(l.milk, "180");
        assertReserved(l.syrup, "20");
        assertReserved(l.ice, "120");
        assertReserved(l.cup, "1");
        assertReserved(l.lid, "1");
        for (UUID ingredient : List.of(l.beans, l.milk, l.syrup, l.ice, l.cup, l.lid)) {
            assertOnHand(ingredient, STOCK_ON_HAND);
        }

        // Exactly two reservation rows: one converted cart row, one active order row.
        assertEquals(2L, jdbc.queryForObject("select count(*) from inventory.inventory_reservations "
                + "where store_id=?", Long.class, storeId));
        assertEquals(1L, jdbc.queryForObject("select count(*) from inventory.inventory_reservations "
                + "where store_id=? and source_type='CART_ITEM'", Long.class, storeId));
        assertEquals(1L, jdbc.queryForObject("select count(*) from inventory.inventory_reservations "
                + "where store_id=? and source_type='ORDER'", Long.class, storeId));

        // No-release-gap: the only ledger row is the one written when the line was
        // added to the cart. Nothing was released and nothing was consumed.
        assertEquals(0, ledgerHeaders("SALE_RELEASE"));
        assertEquals(0, jdbc.queryForObject("select count(*) from inventory.stock_transactions "
                + "where store_id=? and transaction_type='SALE_CONSUMPTION'", Long.class, storeId));
        assertEquals(1, ledgerHeadersForCart(cart.cartId(), "SALE_RESERVATION"));
        assertEquals(6, ledgerLines(cart.cartId(), "SALE_RESERVATION"));

        assertEquals("CHECKED_OUT", jdbc.queryForObject("select status from cart.carts where cart_id=?",
                String.class, cart.cartId()));
    }

    // ------------------------------------------------- snapshot authority

    @Test
    void checkoutUsesThePersistedCartSnapshotNotTheCurrentRecipe() {
        Latte l = latte();
        CartResponse cart = add(l, 1);
        UUID itemId = onlyItem(cart).cartItemId();
        assertReserved(l.beans, "18");
        assertReserved(l.cup, "1");

        // The recipe is rewritten AFTER the line was added: if checkout recalculated
        // demand it would hold 40 g of beans and 3 cups instead of the snapshot.
        assignRecipe(l.productId, "latte 12oz v2", List.of(
                item(l.beans, "40", 0), item(l.milk, "180", 1), item(l.syrup, "20", 2),
                item(l.ice, "120", 3), item(l.cup, "3", 4), item(l.lid, "1", 5)));

        OrderResponse order = checkouts.checkout(userId, UUID.randomUUID(),
                new CreateOrderRequest(cart.cartId(), "PICKUP", null));

        UUID orderReservation = orderReservationId(order.orderId());
        assertQuantity(orderReservation, l.beans, "18", "the snapshot wins over the new recipe");
        assertQuantity(orderReservation, l.cup, "1", "packaging must stay at the snapshot quantity");
        assertEquals(6L, orderReservationLineCount(order.orderId()));
        assertReserved(l.beans, "18");
        assertReserved(l.cup, "1");
        assertOnHand(l.beans, STOCK_ON_HAND);
        assertEquals("CONVERTED", reservationColumn(itemId, "status"));
        assertEquals(0, ledgerHeaders("SALE_RELEASE"));
    }

    // ------------------------------------------------ legacy line + rollback

    @Test
    void aFailedLegacyReservationKeepsTheCartHoldAndStaysRetryable() {
        Latte l = latte();
        // Cart demand for this line is empty (FINISHED_GOOD), order demand is not.
        UUID sprinkles = seedIngredient("SPRINKLES_" + suffix(), GRAM);
        seedBalance(sprinkles, "20");
        UUID cake = seedProduct("CAKE_" + suffix());
        jdbc.update("update catalog.products set stock_mode='FINISHED_GOOD' where product_id=?", cake);
        offerProduct(storeId, cake);
        assignRecipe(cake, "cake 1pc", List.of(item(sprinkles, "50", 0)));

        CartResponse first = add(l, 1);
        UUID itemId = onlyItem(first).cartItemId();
        CartResponse cart = addProduct(cake, 1);
        assertEquals(2, cart.items().size(), "both lines are in the cart");
        assertEquals(first.cartId(), cart.cartId(), "the second line joins the same cart");
        assertReserved(l.beans, "18");
        assertReserved(sprinkles, "0");
        // The line was added while it planned no raw demand (a legacy shape: a line
        // that never held stock). It has recipe demand by the time it is checked
        // out, so checkout is where that demand first has to be reserved.
        jdbc.update("update catalog.products set stock_mode='RECIPE_BASED' where product_id=?", cake);

        UUID key = UUID.randomUUID();
        CreateOrderRequest request = new CreateOrderRequest(cart.cartId(), "PICKUP", null);
        ConflictException ex = assertThrows(ConflictException.class, () -> checkouts.checkout(userId, key, request));
        assertEquals("INSUFFICIENT_STOCK", ex.code());

        // Nothing of the conversion survives: the cart keeps its ACTIVE hold.
        assertEquals("ACTIVE", reservationColumn(itemId, "status"));
        assertNull(reservationColumn(itemId, "converted_at"));
        assertNull(reservationColumn(itemId, "converted_order_id"));
        assertQuantity(reservationId(itemId), l.beans, "18", "the cart hold is unchanged by the rollback");
        assertReserved(l.beans, "18");
        assertReserved(l.milk, "180");
        assertReserved(sprinkles, "0");
        assertEquals(0, ledgerHeaders("SALE_RELEASE"), "no release may survive the rollback");
        assertEquals(1, ledgerHeaders("SALE_RESERVATION"), "no legacy reservation row may survive either");
        assertEquals(0L, jdbc.queryForObject("select count(*) from ordering.orders where cart_id=?", Long.class,
                cart.cartId()), "the order row rolls back too");
        assertEquals(0L, jdbc.queryForObject("select count(*) from inventory.inventory_reservations "
                + "where store_id=? and order_id is not null", Long.class, storeId));
        assertEquals("ACTIVE", jdbc.queryForObject("select status from cart.carts where cart_id=?",
                String.class, cart.cartId()), "the customer keeps a usable cart");
        assertNoConsumption();

        // Same key, now satisfied: the failed attempt left no idempotency block.
        setOnHand(sprinkles, STOCK_ON_HAND);
        OrderResponse order = checkouts.checkout(userId, key, request);

        assertEquals("CONVERTED", reservationColumn(itemId, "status"));
        assertNull(reservationColumn(itemId, "release_reason"));
        assertEquals("ACTIVE", orderReservationColumn(order.orderId(), "status"));
        assertReserved(l.beans, "18");
        assertReserved(sprinkles, "50");
        // 6 snapshot lines plus the one legacy line reserved at checkout.
        assertEquals(7L, orderReservationLineCount(order.orderId()));
        assertQuantity(orderReservationId(order.orderId()), sprinkles, "50", "the legacy part is now held");

        // One audited SALE_RESERVATION for the legacy part, reason
        // LEGACY_CHECKOUT_RESERVE, still no release anywhere.
        assertEquals(2, ledgerHeaders("SALE_RESERVATION"));
        assertEquals(1L, jdbc.queryForObject("select count(*) from inventory.stock_transactions "
                + "where store_id=? and transaction_type='SALE_RESERVATION' and reason='LEGACY_CHECKOUT_RESERVE'",
                Long.class, storeId));
        assertEquals(0, ledgerHeaders("SALE_RELEASE"));
        assertEquals("CHECKED_OUT", jdbc.queryForObject("select status from cart.carts where cart_id=?",
                String.class, cart.cartId()));
        assertNoConsumption();
    }

    // ------------------------------------------------------ one order per cart

    @Test
    void oneCartProducesExactlyOneOrderAndOneConversion() {
        Latte l = latte();
        CartResponse cart = add(l, 1);
        UUID itemId = onlyItem(cart).cartItemId();
        UUID key = UUID.randomUUID();
        CreateOrderRequest request = new CreateOrderRequest(cart.cartId(), "PICKUP", null);

        OrderResponse first = checkouts.checkout(userId, key, request);
        OrderResponse replay = checkouts.checkout(userId, key, request);

        assertEquals(first.orderId(), replay.orderId(), "the same key replays the same order");
        assertEquals(1L, jdbc.queryForObject("select count(*) from ordering.orders where cart_id=?", Long.class,
                cart.cartId()));
        assertEquals(1L, jdbc.queryForObject("select count(*) from inventory.inventory_reservations "
                + "where store_id=? and order_id=?", Long.class, storeId, first.orderId()));

        ConflictException conflict = assertThrows(ConflictException.class,
                () -> checkouts.checkout(userId, UUID.randomUUID(), request));
        assertEquals("CART_ALREADY_CHECKED_OUT", conflict.code());
        assertEquals(1L, jdbc.queryForObject("select count(*) from ordering.orders where cart_id=?", Long.class,
                cart.cartId()), "a second checkout cannot create a second order");

        // The hold is still held exactly once, by the single order reservation.
        assertReserved(l.beans, "18");
        assertEquals("CONVERTED", reservationColumn(itemId, "status"));
        assertEquals(orderReservationId(first.orderId()).toString(),
                reservationColumn(itemId, "converted_to_reservation_id"));
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

    private UUID assignRecipe(UUID productId, String name, List<RecipeItemRequest> items) {
        RecipeVersionResponse draft = recipeCommands.createDraft(admin, productId,
                new CreateRecipeDraftRequest(BigDecimal.ONE, null, name, items));
        RecipeVersionResponse active = recipeCommands.activate(admin, draft.recipeVersionId(), draft.version());
        assignment.assignProduct(admin, productId, new AssignProductRecipeRequest(active.recipeVersionId(),
                productVersion(productId)));
        return active.recipeVersionId();
    }

    private CartResponse add(Latte l, int quantity) {
        return carts.add(userId, null,
                new AddCartItemRequest(storeId, l.productId(), null, quantity, null, List.of()));
    }

    private CartResponse addProduct(UUID productId, int quantity) {
        return carts.add(userId, null,
                new AddCartItemRequest(storeId, productId, null, quantity, null, List.of()));
    }

    private static CartItemResponse onlyItem(CartResponse cart) {
        assertEquals(1, cart.items().size(), "expected exactly one cart line");
        return cart.items().get(0);
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
                "current_stock for " + ingredient + " must never move before settlement");
    }

    private void assertNoConsumption() {
        assertEquals(0, jdbc.queryForObject("select count(*) from inventory.stock_transactions "
                + "where transaction_type='SALE_CONSUMPTION' and store_id=?", Long.class, storeId),
                "no SALE_CONSUMPTION row may exist for this store");
    }

    private BigDecimal reserved(UUID ingredient) {
        return jdbc.queryForObject("select reserved_stock from inventory.inventory_balances "
                + "where store_id=? and ingredient_id=?", BigDecimal.class, storeId, ingredient);
    }

    private long ledgerHeaders(String type) {
        return jdbc.queryForObject("select count(*) from inventory.stock_transactions "
                + "where store_id=? and transaction_type=?", Long.class, storeId, type);
    }

    private long ledgerHeadersForCart(UUID cartId, String type) {
        return jdbc.queryForObject("select count(*) from inventory.stock_transactions "
                + "where cart_id=? and transaction_type=?", Long.class, cartId, type);
    }

    private long ledgerLines(UUID cartId, String type) {
        return jdbc.queryForObject("select count(*) from inventory.stock_transaction_lines l "
                + "join inventory.stock_transactions t on t.stock_transaction_id=l.stock_transaction_id "
                + "where t.cart_id=? and t.transaction_type=?", Long.class, cartId, type);
    }

    private UUID reservationId(UUID cartItemId) {
        return jdbc.queryForObject("select reservation_id from inventory.inventory_reservations "
                + "where cart_item_id=?", UUID.class, cartItemId);
    }

    private String reservationColumn(UUID cartItemId, String column) {
        return jdbc.queryForObject("select cast(" + column + " as text) from inventory.inventory_reservations "
                + "where cart_item_id=?", String.class, cartItemId);
    }

    private UUID orderReservationId(UUID orderId) {
        return jdbc.queryForObject("select reservation_id from inventory.inventory_reservations where order_id=?",
                UUID.class, orderId);
    }

    private String orderReservationColumn(UUID orderId, String column) {
        return jdbc.queryForObject("select cast(" + column + " as text) from inventory.inventory_reservations "
                + "where order_id=?", String.class, orderId);
    }

    private long orderReservationLineCount(UUID orderId) {
        return jdbc.queryForObject("select count(*) from inventory.inventory_reservation_lines l "
                + "join inventory.inventory_reservations r on r.reservation_id=l.reservation_id where r.order_id=?",
                Long.class, orderId);
    }

    private void assertQuantity(UUID reservationId, UUID ingredient, String expected, String message) {
        BigDecimal actual = jdbc.queryForObject("select quantity_reserved from "
                + "inventory.inventory_reservation_lines where reservation_id=? and ingredient_id=?",
                BigDecimal.class, reservationId, ingredient);
        assertNotNull(actual, message + " (no reservation line for " + ingredient + ")");
        assertEquals(0, dec(expected).compareTo(actual), message + " (expected " + expected + " but was " + actual + ")");
    }

    // --------------------------------------------------------- raw seeding

    private void setOnHand(UUID ingredient, String current) {
        jdbc.update("update inventory.inventory_balances set current_stock=? where store_id=? and ingredient_id=?",
                dec(current), storeId, ingredient);
    }

    private UUID seedStore() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?, ?, 'Cart Handoff Branch', 'Addr', 'Asia/Phnom_Penh', 'USD', 'en-US', true, now(), now(), 0)",
                id, "CHO" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        return id;
    }

    private UUID seedCustomer() {
        UUID id = UUID.randomUUID();
        String email = "handoff-it-" + id + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)", id, "Handoff IT Customer", email, email, "en-US");
        jdbc.update("insert into customer.customer_profiles (customer_id, user_id, marketing_opt_in, created_at, "
                + "updated_at, version) values (?,?,false,now(),now(),0)", UUID.randomUUID(), id);
        return id;
    }

    private CurrentActor seedActor(String roleId) {
        UUID id = UUID.randomUUID();
        String email = "handoff-it-admin-" + id + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)", id, "Handoff IT Admin", email, email, "en-US");
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

    private void offerProduct(UUID store, UUID productId) {
        jdbc.update("insert into catalog.store_products (store_id, product_id, available, updated_at, version) "
                + "values (?,?,true,now(),0)", store, productId);
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

    private long productVersion(UUID productId) {
        return jdbc.queryForObject("select version from catalog.products where product_id=?", Long.class, productId);
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
