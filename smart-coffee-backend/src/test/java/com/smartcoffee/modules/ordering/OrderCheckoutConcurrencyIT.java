package com.smartcoffee.modules.ordering;

import static org.junit.jupiter.api.Assertions.*;

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
 * Slice 2D §16/§17-A — two committed checkouts of one cart.
 *
 * <p>The cart row lock serializes the two attempts, so exactly one order is
 * created, exactly one order reservation receives the converted snapshot, and
 * exactly one {@code OrderPlaced} event is written. The loser is rejected with
 * the stable {@code CART_ALREADY_CHECKED_OUT} conflict, and the held quantity is
 * never doubled.
 */
class OrderCheckoutConcurrencyIT extends IntegrationTestBase {

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

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private CurrentActor admin;
    private UUID storeId;
    private UUID userId;
    private UUID beans;
    private UUID productId;

    @BeforeEach
    void setUp() {
        admin = seedActor(SUPER_ADMIN);
        storeId = seedStore();
        userId = seedCustomer();
        beans = seedIngredient("BEANS_" + suffix(), GRAM);
        UUID milk = seedIngredient("MILK_" + suffix(), MILLILITER);
        UUID cup = seedIngredient("CUP12_" + suffix(), PIECE);
        for (UUID ingredient : List.of(beans, milk, cup)) {
            seedBalance(ingredient, "1000");
        }
        UUID product = seedProduct("CONCURRENT_" + suffix());
        offerProduct(product);
        assignRecipe(product, List.of(item(beans, "18", 0), item(milk, "180", 1), item(cup, "1", 2)));
        productId = product;
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        SecurityContextHolder.clearContext();
    }

    @Test
    void oneCartCreatesOneOrder() throws Exception {
        CartResponse cart = carts.add(userId, null,
                new AddCartItemRequest(storeId, productId, null, 1, null, List.of()));
        CreateOrderRequest request = new CreateOrderRequest(cart.cartId(), "PICKUP", null);

        List<Object> results = race(() -> checkouts.checkout(userId, UUID.randomUUID(), request),
                () -> checkouts.checkout(userId, UUID.randomUUID(), request));

        assertEquals(1, results.stream().filter(r -> r instanceof OrderResponse).count(),
                "exactly one checkout may commit: " + results);

        assertEquals(1L, orderCount(cart.cartId()), "one cart creates exactly one order");
        assertEquals(1L, jdbc.queryForObject("select count(*) from inventory.inventory_reservations "
                + "where store_id=? and order_id is not null", Long.class, storeId),
                "one converted order reservation");
        assertEquals(1L, jdbc.queryForObject("select count(*) from inventory.inventory_reservations "
                + "where store_id=? and source_type='CART_ITEM' and status='CONVERTED'", Long.class, storeId));
        assertEquals(1L, jdbc.queryForObject("select count(*) from integration.outbox_events "
                + "where event_type='OrderPlaced' and aggregate_id in "
                + "(select order_id from ordering.orders where cart_id=?)", Long.class, cart.cartId()),
                "exactly one OrderPlaced event");
        assertEquals(0, dec("18").compareTo(reserved()), "the hold is transferred, never doubled");
        assertEquals(0L, jdbc.queryForObject("select count(*) from inventory.stock_transactions "
                + "where store_id=? and transaction_type='SALE_RELEASE'", Long.class, storeId),
                "a conversion never releases the held quantity");
    }

    // ------------------------------------------------------------- plumbing

    private long orderCount(UUID cartId) {
        return jdbc.queryForObject("select count(*) from ordering.orders where cart_id=?", Long.class, cartId);
    }

    private BigDecimal reserved() {
        return jdbc.queryForObject("select reserved_stock from inventory.inventory_balances "
                + "where store_id=? and ingredient_id=?", BigDecimal.class, storeId, beans);
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
                new CreateRecipeDraftRequest(BigDecimal.ONE, null, "concurrent " + suffix(), items));
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
                + "values (?, ?, 'Checkout Race Branch', 'Addr', 'Asia/Phnom_Penh', 'USD', 'en-US', true, now(), now(), 0)",
                id, "CR" + UUID.randomUUID().toString().substring(0, 7).toUpperCase());
        return id;
    }

    private UUID seedCustomer() {
        UUID id = UUID.randomUUID();
        String email = "checkout-race-" + id + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)", id, "Checkout Race Customer", email, email, "en-US");
        jdbc.update("insert into customer.customer_profiles (customer_id, user_id, marketing_opt_in, created_at, "
                + "updated_at, version) values (?,?,false,now(),now(),0)", UUID.randomUUID(), id);
        return id;
    }

    private CurrentActor seedActor(String roleId) {
        UUID id = UUID.randomUUID();
        String email = "checkout-race-admin-" + id + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)", id, "Checkout Race Admin", email, email, "en-US");
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
