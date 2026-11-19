package com.smartcoffee.modules.ordering;

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
 * Slice 2D §9/§16 — a checkout that cannot take the whole demand leaves no
 * trace.
 *
 * <p>The failing case is a line the cart never held ({@code FINISHED_GOOD}: its
 * stock mode plans no raw demand) whose checkout-time demand cannot be reserved.
 * The order row, the order reservation, the conversion of the already-held line,
 * the legacy reservation, the ledger rows and the {@code OrderPlaced} event must
 * all disappear with the failed transaction, the cart must stay usable, and the
 * idempotency claim must be released so the same key can be retried.
 */
class OrderCheckoutRollbackIT extends IntegrationTestBase {

    private static final String SUPER_ADMIN = "00000000-0000-0000-0000-000000000002";
    private static final UUID GRAM = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final UUID MILLILITER = UUID.fromString("60000000-0000-0000-0000-000000000003");
    private static final UUID PIECE = UUID.fromString("60000000-0000-0000-0000-000000000005");

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

    @Test
    void insufficientInventoryRollsBackOrderAndCart() {
        UUID beans = seedIngredient("BEANS_" + suffix(), GRAM);
        UUID milk = seedIngredient("MILK_" + suffix(), MILLILITER);
        UUID cup = seedIngredient("CUP12_" + suffix(), PIECE);
        for (UUID ingredient : List.of(beans, milk, cup)) {
            seedBalance(ingredient, "1000");
        }
        UUID latte = seedProduct("LATTE_" + suffix());
        offerProduct(latte);
        assignRecipe(latte, List.of(item(beans, "18", 0), item(milk, "180", 1), item(cup, "1", 2)));

        // A finished good plans no cart demand, but its recipe demand must be held
        // at checkout — and there is not enough of it.
        UUID sprinkles = seedIngredient("SPRINKLES_" + suffix(), GRAM);
        seedBalance(sprinkles, "10");
        UUID cake = seedProduct("CAKE_" + suffix());
        jdbc.update("update catalog.products set stock_mode='FINISHED_GOOD' where product_id=?", cake);
        offerProduct(cake);
        assignRecipe(cake, List.of(item(sprinkles, "50", 0)));

        CartResponse cart = carts.add(userId, null,
                new AddCartItemRequest(storeId, latte, null, 1, null, List.of()));
        cart = carts.add(userId, null, new AddCartItemRequest(storeId, cake, null, 1, null, List.of()));
        UUID heldLine = cart.items().stream().filter(i -> i.productId().equals(latte)).findFirst()
                .orElseThrow(() -> new AssertionError("the latte line must be in the cart")).cartItemId();
        assertEquals("ACTIVE", reservationStatus(heldLine));
        // The cake line never held stock (added while it planned no raw demand), and
        // it does have recipe demand by checkout: that is the legacy shape where
        // checkout itself must reserve — and cannot.
        jdbc.update("update catalog.products set stock_mode='RECIPE_BASED' where product_id=?", cake);

        UUID key = UUID.randomUUID();
        CreateOrderRequest request = new CreateOrderRequest(cart.cartId(), "PICKUP", null);
        ConflictException ex = assertThrows(ConflictException.class, () -> checkouts.checkout(userId, key, request));
        assertEquals("INSUFFICIENT_STOCK", ex.code());

        // Nothing of the attempt survives.
        assertEquals(0L, jdbc.queryForObject("select count(*) from ordering.orders where cart_id=?", Long.class,
                cart.cartId()), "no order row may survive");
        assertEquals(0L, jdbc.queryForObject("select count(*) from inventory.inventory_reservations "
                + "where store_id=? and order_id is not null", Long.class, storeId), "no order reservation");
        assertEquals(0L, jdbc.queryForObject("select count(*) from integration.outbox_events "
                + "where aggregate_type='ORDER' and payload->>'storeId'=?", Long.class, storeId.toString()),
                "no OrderPlaced event");
        assertEquals(0L, jdbc.queryForObject("select count(*) from integration.api_idempotency_records "
                + "where scope='ORDER_CREATE' and idempotency_key=? and status='COMPLETED'", Long.class, key),
                "the claim must not be completed");

        // The cart keeps its hold, its lines and its ACTIVE status.
        assertEquals("ACTIVE", jdbc.queryForObject("select status from cart.carts where cart_id=?",
                String.class, cart.cartId()));
        assertEquals("ACTIVE", reservationStatus(heldLine));
        assertNull(jdbc.queryForObject("select cast(converted_at as text) from inventory.inventory_reservations "
                + "where cart_item_id=?", String.class, heldLine));
        assertEquals(0, dec("18").compareTo(reserved(beans)));
        assertEquals(0, dec("0").compareTo(reserved(sprinkles)));
        assertEquals(0, dec("1000").compareTo(onHand(beans)));

        // The same key is retryable once the missing stock exists.
        setOnHand(sprinkles, "1000");
        OrderResponse order = checkouts.checkout(userId, key, request);

        assertEquals(1L, jdbc.queryForObject("select count(*) from ordering.orders where cart_id=?", Long.class,
                cart.cartId()));
        assertEquals("CONVERTED", reservationStatus(heldLine));
        assertEquals(order.orderId().toString(), jdbc.queryForObject(
                "select cast(converted_order_id as text) from inventory.inventory_reservations where cart_item_id=?",
                String.class, heldLine));
        assertEquals(0, dec("18").compareTo(reserved(beans)));
        assertEquals(0, dec("50").compareTo(reserved(sprinkles)));
        assertEquals(0, dec("1000").compareTo(onHand(beans)), "checkout never moves on-hand stock");
        assertEquals("CHECKED_OUT", jdbc.queryForObject("select status from cart.carts where cart_id=?",
                String.class, cart.cartId()));
    }

    // ------------------------------------------------------------- plumbing

    private String reservationStatus(UUID cartItemId) {
        return jdbc.queryForObject("select cast(status as text) from inventory.inventory_reservations "
                + "where cart_item_id=?", String.class, cartItemId);
    }

    private BigDecimal reserved(UUID ingredient) {
        return jdbc.queryForObject("select reserved_stock from inventory.inventory_balances "
                + "where store_id=? and ingredient_id=?", BigDecimal.class, storeId, ingredient);
    }

    private BigDecimal onHand(UUID ingredient) {
        return jdbc.queryForObject("select current_stock from inventory.inventory_balances "
                + "where store_id=? and ingredient_id=?", BigDecimal.class, storeId, ingredient);
    }

    private void setOnHand(UUID ingredient, String current) {
        jdbc.update("update inventory.inventory_balances set current_stock=? where store_id=? and ingredient_id=?",
                dec(current), storeId, ingredient);
    }

    // ------------------------------------------------------------- fixtures

    private UUID assignRecipe(UUID productId, List<RecipeItemRequest> items) {
        RecipeVersionResponse draft = recipeCommands.createDraft(admin, productId,
                new CreateRecipeDraftRequest(BigDecimal.ONE, null, "rollback " + suffix(), items));
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
                + "values (?, ?, 'Checkout Rollback Branch', 'Addr', 'Asia/Phnom_Penh', 'USD', 'en-US', true, now(), now(), 0)",
                id, "RB" + UUID.randomUUID().toString().substring(0, 7).toUpperCase());
        return id;
    }

    private UUID seedCustomer() {
        UUID id = UUID.randomUUID();
        String email = "checkout-rollback-" + id + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)", id, "Checkout Rollback Customer", email, email,
                "en-US");
        jdbc.update("insert into customer.customer_profiles (customer_id, user_id, marketing_opt_in, created_at, "
                + "updated_at, version) values (?,?,false,now(),now(),0)", UUID.randomUUID(), id);
        return id;
    }

    private CurrentActor seedActor(String roleId) {
        UUID id = UUID.randomUUID();
        String email = "checkout-rollback-admin-" + id + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)", id, "Checkout Rollback Admin", email, email,
                "en-US");
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
