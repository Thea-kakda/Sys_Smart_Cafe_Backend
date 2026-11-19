package com.smartcoffee.modules.cart;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.cart.api.AddCartItemRequest;
import com.smartcoffee.modules.cart.api.CartResponse;
import com.smartcoffee.modules.cart.application.CartService;
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
 * Slice 2C §11/§19-G — the two races that matter for cart reservations:
 *
 * <ol>
 *   <li><b>last stock</b>: two carts reserve the only remaining unit at the same
 *       time; exactly one wins, the other gets a stable {@code INSUFFICIENT_STOCK}
 *       conflict, and the store never holds more than it has.</li>
 *   <li><b>overlapping baskets</b>: two carts hold the same two ingredients but
 *       were configured in opposite order, so an unsorted lock sequence would
 *       deadlock. Both must always commit.</li>
 * </ol>
 *
 * <p>Both tests run the real {@link CartService} on two threads against real
 * PostgreSQL row locks — the guarded conditional update is the only thing
 * preventing the oversell, so the test would fail if that guard were relaxed.
 */
class CartReservationConcurrencyIT extends IntegrationTestBase {

    private static final String SUPER_ADMIN = "00000000-0000-0000-0000-000000000002";
    private static final UUID PIECE = UUID.fromString("60000000-0000-0000-0000-000000000005");
    private static final UUID GRAM = UUID.fromString("60000000-0000-0000-0000-000000000001");

    @Autowired JdbcTemplate jdbc;
    @Autowired CurrentActorProvider actors;
    @Autowired CartService carts;
    @Autowired RecipeCommandService recipeCommands;
    @Autowired RecipeAssignmentService assignment;

    private final ExecutorService pool = Executors.newFixedThreadPool(2);
    private CurrentActor admin;
    private UUID storeId;

    @BeforeEach
    void setUp() {
        admin = seedActor(SUPER_ADMIN);
        storeId = seedStore();
    }

    @AfterEach
    void tearDown() {
        pool.shutdownNow();
        SecurityContextHolder.clearContext();
    }

    @Test
    void onlyOneCartCanTakeTheLastUnit() throws Exception {
        UUID cup = seedIngredient("CUP_LAST_" + suffix(), PIECE);
        seedBalance(cup, "1");
        UUID productId = productWithRecipe("LAST_" + suffix(), List.of(line(cup, "1", 0)));
        UUID userA = seedCustomer();
        UUID userB = seedCustomer();
        AddCartItemRequest request = new AddCartItemRequest(storeId, productId, null, 1, null, List.of());

        List<Object> results = race(() -> carts.add(userA, null, request),
                () -> carts.add(userB, null, request));

        assertEquals(1, results.stream().filter(r -> r instanceof CartResponse).count(),
                "exactly one reservation may win: " + results);
        ConflictException loser = (ConflictException) results.stream()
                .filter(r -> r instanceof ConflictException).findFirst()
                .orElseThrow(() -> new AssertionError("the loser must fail with a business conflict: " + results));
        assertEquals("INSUFFICIENT_STOCK", loser.code());

        // No oversell: one unit held, none available, one active reservation, one
        // ledger row, and one cart line.
        assertEquals(0, dec("1").compareTo(reserved(cup)), "reserved_stock must be exactly 1");
        assertEquals(0, dec("0").compareTo(available(cup)), "available must not go negative");
        assertEquals(0, dec("1").compareTo(onHand(cup)), "on hand never changes");
        assertEquals(1L, jdbc.queryForObject("select count(*) from inventory.inventory_reservations "
                + "where store_id=? and cart_item_id is not null and status='ACTIVE'", Long.class, storeId));
        assertEquals(1L, jdbc.queryForObject("select count(*) from inventory.stock_transactions "
                + "where store_id=? and transaction_type='SALE_RESERVATION'", Long.class, storeId));
        assertEquals(1L, jdbc.queryForObject("select count(*) from cart.cart_items i join cart.carts c "
                + "on c.cart_id=i.cart_id where c.store_id=?", Long.class, storeId));
        assertEquals(0L, jdbc.queryForObject("select count(*) from inventory.stock_transactions "
                + "where store_id=? and transaction_type='SALE_CONSUMPTION'", Long.class, storeId));
    }

    @Test
    void overlappingBasketsConfiguredInOppositeOrderDoNotDeadlock() throws Exception {
        UUID beans = seedIngredient("BEANS_" + suffix(), GRAM);
        UUID milk = seedIngredient("MILK_" + suffix(), GRAM);
        seedBalance(beans, "1000");
        seedBalance(milk, "1000");
        // Same two ingredients, declared in opposite order: recipe line order must
        // not decide the balance-lock order.
        UUID productA = productWithRecipe("ORDER_AB_" + suffix(),
                List.of(line(beans, "10", 0), line(milk, "20", 1)));
        UUID productB = productWithRecipe("ORDER_BA_" + suffix(),
                List.of(line(milk, "20", 0), line(beans, "10", 1)));
        UUID userA = seedCustomer();
        UUID userB = seedCustomer();
        AddCartItemRequest requestA = new AddCartItemRequest(storeId, productA, null, 1, null, List.of());
        AddCartItemRequest requestB = new AddCartItemRequest(storeId, productB, null, 1, null, List.of());

        int rounds = 4;
        for (int round = 0; round < rounds; round++) {
            List<Object> results = race(() -> carts.add(userA, null, requestA),
                    () -> carts.add(userB, null, requestB));
            for (Object result : results) {
                assertTrue(result instanceof CartResponse,
                        "round " + round + " must not deadlock or conflict: " + result);
            }
        }

        // 10 g beans and 20 g milk per add, from both baskets in every round.
        assertEquals(0, dec(String.valueOf(rounds * 2 * 10)).compareTo(reserved(beans)),
                "every add held its beans exactly once");
        assertEquals(0, dec(String.valueOf(rounds * 2 * 20)).compareTo(reserved(milk)),
                "every add held its milk exactly once");
        assertEquals(0, dec("1000").compareTo(onHand(beans)));
    }

    // ------------------------------------------------------------- plumbing

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

    private UUID productWithRecipe(String code, List<RecipeItemRequest> lines) {
        UUID category = jdbc.queryForObject(
                "select category_id from catalog.product_categories order by category_id limit 1", UUID.class);
        UUID productId = UUID.randomUUID();
        jdbc.update("insert into catalog.products (product_id, product_code, category_id, product_name, base_price, "
                + "currency_code, preparation_minutes, tax_rate, customizable, active, created_at, updated_at, version) "
                + "values (?,?,?,?,?, 'USD', 5, 0.1, false, true, now(), now(), 0)",
                productId, code, category, "IT " + code, dec("10"));
        jdbc.update("insert into catalog.store_products (store_id, product_id, available, updated_at, version) "
                + "values (?,?,true,now(),0)", storeId, productId);
        RecipeVersionResponse draft = recipeCommands.createDraft(admin, productId,
                new CreateRecipeDraftRequest(BigDecimal.ONE, null, code, lines));
        RecipeVersionResponse active = recipeCommands.activate(admin, draft.recipeVersionId(), draft.version());
        assignment.assignProduct(admin, productId, new AssignProductRecipeRequest(active.recipeVersionId(),
                jdbc.queryForObject("select version from catalog.products where product_id=?", Long.class,
                        productId)));
        return productId;
    }

    private static RecipeItemRequest line(UUID ingredient, String qty, int order) {
        return new RecipeItemRequest(ingredient, dec(qty), BigDecimal.ZERO, null, RecipeScalingRule.FIXED, order,
                false, null);
    }

    private BigDecimal reserved(UUID ingredient) {
        return jdbc.queryForObject("select reserved_stock from inventory.inventory_balances "
                + "where store_id=? and ingredient_id=?", BigDecimal.class, storeId, ingredient);
    }

    private BigDecimal onHand(UUID ingredient) {
        return jdbc.queryForObject("select current_stock from inventory.inventory_balances "
                + "where store_id=? and ingredient_id=?", BigDecimal.class, storeId, ingredient);
    }

    private BigDecimal available(UUID ingredient) {
        return jdbc.queryForObject("select current_stock - reserved_stock from inventory.inventory_balances "
                + "where store_id=? and ingredient_id=?", BigDecimal.class, storeId, ingredient);
    }

    private UUID seedStore() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?, ?, 'Cart Race Branch', 'Addr', 'Asia/Phnom_Penh', 'USD', 'en-US', true, now(), now(), 0)",
                id, "RACE" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        return id;
    }

    private UUID seedCustomer() {
        UUID id = UUID.randomUUID();
        String email = "cart-race-" + id + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)", id, "Cart Race Customer", email, email, "en-US");
        jdbc.update("insert into customer.customer_profiles (customer_id, user_id, marketing_opt_in, created_at, "
                + "updated_at, version) values (?,?,false,now(),now(),0)", UUID.randomUUID(), id);
        return id;
    }

    private CurrentActor seedActor(String roleId) {
        UUID id = UUID.randomUUID();
        String email = "cart-race-admin-" + id + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)", id, "Cart Race Admin", email, email, "en-US");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, "
                + "assigned_at) values (?,?,?,?,?)", UUID.randomUUID(), id, UUID.fromString(roleId), null,
                java.sql.Timestamp.from(Instant.now()));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(id.toString(), null, List.of()));
        return actors.require();
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

    private static BigDecimal dec(String value) {
        return new BigDecimal(value);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
