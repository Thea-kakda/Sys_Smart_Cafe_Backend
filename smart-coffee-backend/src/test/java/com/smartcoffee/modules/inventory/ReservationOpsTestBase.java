package com.smartcoffee.modules.inventory;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.modules.cart.api.AddCartItemRequest;
import com.smartcoffee.modules.cart.api.CartResponse;
import com.smartcoffee.modules.cart.application.CartExpirationService;
import com.smartcoffee.modules.cart.application.CartService;
import com.smartcoffee.modules.ordering.api.CreateOrderRequest;
import com.smartcoffee.modules.ordering.api.OrderResponse;
import com.smartcoffee.modules.ordering.application.OrderCheckoutService;
import com.smartcoffee.modules.ordering.application.OrderCommandService;
import com.smartcoffee.modules.payment.api.CashPaymentRequest;
import com.smartcoffee.modules.inventory.application.AdminReservationReleaseService;
import com.smartcoffee.modules.inventory.application.InventoryReconciliationService;
import com.smartcoffee.modules.inventory.application.PaidOrderRecoveryService;
import com.smartcoffee.modules.inventory.persistence.ReservationExpiryClaimRepository;
import com.smartcoffee.modules.payment.application.OrderReservationExpiryService;
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
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Shared seeding + assertion scaffolding for the Slice 2E reservation recovery,
 * reconciliation and operational-hardening integration tests (spec §22/§24).
 *
 * <p>It reuses the proven raw-seeding path of {@link ReservationConsumptionLifecycleIT}
 * (real migrated Postgres, real checkout/settlement/cancellation, real inventory
 * ledger) and layers the Slice 2E entry points on top so each scenario reads as
 * little more than "arrange DB state, invoke the worker/service, assert the rows".
 *
 * <p>Service-level authorization is exercised by constructing a
 * {@link CurrentActor} directly — {@code PermissionEvaluator} and
 * {@code StoreAuthorizationService} only read the actor's permission set and store
 * scope — so a test can hand an actor exactly the codes/scope a scenario needs
 * without seeding RBAC rows. The HTTP test ({@code ReservationManagementHttpIT})
 * is the one that authenticates for real through the security filter chain.
 */
abstract class ReservationOpsTestBase extends IntegrationTestBase {

    protected static final String SUPER_ADMIN = "00000000-0000-0000-0000-000000000002";
    protected static final String STORE_MANAGER = "00000000-0000-0000-0000-000000000004";
    protected static final UUID GRAM = UUID.fromString("60000000-0000-0000-0000-000000000001");
    protected static final UUID MILLILITER = UUID.fromString("60000000-0000-0000-0000-000000000003");
    protected static final UUID PIECE = UUID.fromString("60000000-0000-0000-0000-000000000005");
    protected static final String STOCK_ON_HAND = "1000";

    @Autowired protected JdbcTemplate jdbc;
    @Autowired protected CurrentActorProvider actors;
    @Autowired protected CartService carts;
    @Autowired protected CartExpirationService cartExpiry;
    @Autowired protected OrderCheckoutService checkouts;
    @Autowired protected OrderCommandService commands;
    @Autowired protected PaymentApplicationService payments;
    @Autowired protected OrderReservationExpiryService orderExpiry;
    @Autowired protected RecipeCommandService recipeCommands;
    @Autowired protected RecipeAssignmentService assignment;
    @Autowired protected PaidOrderRecoveryService recovery;
    @Autowired protected InventoryReconciliationService reconciliation;
    @Autowired protected AdminReservationReleaseService release;
    @Autowired protected ReservationExpiryClaimRepository expiryClaims;

    // ------------------------------------------------------------- actors

    /** A global administrator holding every Slice 2E management permission. */
    protected CurrentActor globalAdminActor() {
        return new CurrentActor(persistUser(), "STAFF",
                Set.of(PermissionCodes.RESERVATION_RELEASE, PermissionCodes.RECONCILIATION_VIEW,
                        PermissionCodes.RECONCILIATION_RUN, PermissionCodes.RECONCILIATION_REPAIR),
                Set.of(), true);
    }

    /** A manager scoped to exactly {@code storeId}, holding the management codes. */
    protected CurrentActor managerActor(UUID storeId) {
        return new CurrentActor(persistUser(), "STAFF",
                Set.of(PermissionCodes.RESERVATION_RELEASE, PermissionCodes.RECONCILIATION_VIEW,
                        PermissionCodes.RECONCILIATION_RUN, PermissionCodes.RECONCILIATION_REPAIR),
                Set.of(storeId), false);
    }

    /**
     * Inserts a bare {@code identity.users} row and returns its id. Management
     * actors must resolve to real users because the reconciliation run header and
     * the ledger {@code recorded_by} columns carry FKs to {@code users}.
     */
    private UUID persistUser() {
        UUID id = UUID.randomUUID();
        String email = "ops-actor-" + id + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)", id, "Ops Actor", email, email, "en-US");
        return id;
    }

    // ------------------------------------------------------------ fixture

    /** A latte recipe over six ingredients, each seeded to 1000 on hand. */
    protected record Latte(UUID productId, UUID beans, UUID milk, UUID cup) {
    }

    protected Latte latte() {
        UUID productId = seedProduct("LATTE_" + suffix());
        offerProduct(storeId, productId);
        UUID beans = seedIngredient("BEANS_" + suffix(), GRAM);
        UUID milk = seedIngredient("MILK_" + suffix(), MILLILITER);
        UUID cup = seedIngredient("CUP12_" + suffix(), PIECE);
        for (UUID ingredient : List.of(beans, milk, cup)) {
            seedBalance(ingredient, STOCK_ON_HAND);
        }
        assignRecipe(productId, "latte small", List.of(
                item(beans, "18", 0), item(milk, "180", 1), item(cup, "1", 2)));
        return new Latte(productId, beans, milk, cup);
    }

    protected UUID storeId;

    protected void seedStoreAndCustomer() {
        storeId = seedStore();
        actorUserId = seedCustomer();
    }

    protected UUID actorUserId;

    /** Adds one latte and checks it out; the order hold is ACTIVE and unpaid. */
    protected OrderResponse checkoutHeld(Latte l) {
        CartResponse cart = carts.add(actorUserId, null,
                new AddCartItemRequest(storeId, l.productId(), null, 1, null, List.of()));
        OrderResponse order = checkouts.checkout(actorUserId, UUID.randomUUID(),
                new CreateOrderRequest(cart.cartId(), "PICKUP", null));
        assertEquals("ACTIVE", reservationColumn(orderReservationId(order.orderId()), "status"));
        assertEquals(0, dec("18").compareTo(reserved(l.beans())), "checkout holds the snapshot quantity");
        assertOnHand(l.beans, STOCK_ON_HAND);
        return order;
    }

    /** Adds one latte but leaves it in the cart (an ACTIVE CART_ITEM hold, no order). */
    protected CartResponse addToCart(Latte l) {
        CartResponse cart = carts.add(actorUserId, null,
                new AddCartItemRequest(storeId, l.productId(), null, 1, null, List.of()));
        assertEquals(0, dec("18").compareTo(reserved(l.beans())), "add-to-cart holds the demand");
        assertOnHand(l.beans, STOCK_ON_HAND);
        return cart;
    }

    protected void settle(UUID orderId, String received) {
        payments.cash(actorUserId, UUID.randomUUID(),
                new CashPaymentRequest(orderId, storeId, dec(received), "USD", null));
    }

    // ------------------------------------------------------- SQL state surgery

    /** Backdates the cart so the expiry worker treats it as elapsed. */
    protected void backdateCart(UUID cartId) {
        jdbc.update("update cart.carts set created_at = now() - interval '3 days', "
                + "expires_at = now() - interval '1 day' where cart_id = ?", cartId);
    }

    /** Moves one reservation's expiry into the past (order-held cleanup candidate). */
    protected void backdateReservation(UUID reservationId) {
        // ck_reservation_expiry enforces expires_at > created_at, so both must move.
        jdbc.update("update inventory.inventory_reservations set created_at = now() - interval '2 hours', "
                + "updated_at = now() - interval '2 hours', expires_at = now() - interval '1 hour' "
                + "where reservation_id = ?", reservationId);
    }

    protected String reservationStatus(UUID reservationId) {
        return reservationColumn(reservationId, "status");
    }

    // ------------------------------------------------------------- assertions

    protected void assertReserved(UUID ingredient, String expected) {
        assertEquals(0, dec(expected).compareTo(reserved(ingredient)),
                "reserved_stock for " + ingredient + " (expected " + expected + ")");
    }

    protected void assertOnHand(UUID ingredient, String expected) {
        assertEquals(0, dec(expected).compareTo(current(ingredient)),
                "current_stock for " + ingredient + " (expected " + expected + ")");
    }

    protected BigDecimal reserved(UUID ingredient) {
        return jdbc.queryForObject("select reserved_stock from inventory.inventory_balances "
                + "where store_id = ? and ingredient_id = ?", BigDecimal.class, storeId, ingredient);
    }

    protected BigDecimal current(UUID ingredient) {
        return jdbc.queryForObject("select current_stock from inventory.inventory_balances "
                + "where store_id = ? and ingredient_id = ?", BigDecimal.class, storeId, ingredient);
    }

    protected long ledgerCount(String type) {
        return jdbc.queryForObject("select count(*) from inventory.stock_transactions "
                + "where store_id = ? and transaction_type = ?", Long.class, storeId, type);
    }

    protected long ledgerCount(String type, UUID reservationId) {
        return jdbc.queryForObject("select count(*) from inventory.stock_transactions "
                + "where reservation_id = ? and transaction_type = ?", Long.class, reservationId, type);
    }

    protected String ledgerReason(String type) {
        return jdbc.queryForObject("select reason from inventory.stock_transactions "
                + "where store_id = ? and transaction_type = ? order by created_at desc limit 1",
                String.class, storeId, type);
    }

    protected UUID orderReservationId(UUID orderId) {
        return jdbc.queryForObject("select reservation_id from inventory.inventory_reservations where order_id = ?",
                UUID.class, orderId);
    }

    protected UUID cartReservationId(UUID cartId) {
        return jdbc.queryForObject("select reservation_id from inventory.inventory_reservations "
                + "where cart_id = ? and source_type = 'CART_ITEM'", UUID.class, cartId);
    }

    protected String reservationColumn(UUID reservationId, String column) {
        return jdbc.queryForObject("select cast(" + column + " as text) from inventory.inventory_reservations "
                + "where reservation_id = ?", String.class, reservationId);
    }

    protected long reservationVersion(UUID reservationId) {
        return jdbc.queryForObject("select version from inventory.inventory_reservations where reservation_id = ?",
                Long.class, reservationId);
    }

    protected void assertNoConsumption() {
        assertEquals(0L, ledgerCount("SALE_CONSUMPTION"), "no SALE_CONSUMPTION row may exist for this store");
    }

    // ----------------------------------------------------------- raw seeding

    private void assignRecipe(UUID productId, String name, List<RecipeItemRequest> items) {
        RecipeVersionResponse draft = recipeCommands.createDraft(globalActorForSeed(), productId,
                new CreateRecipeDraftRequest(BigDecimal.ONE, null, name, items));
        RecipeVersionResponse active = recipeCommands.activate(globalActorForSeed(), draft.recipeVersionId(),
                draft.version());
        assignment.assignProduct(globalActorForSeed(), productId,
                new AssignProductRecipeRequest(active.recipeVersionId(),
                        jdbc.queryForObject("select version from catalog.products where product_id = ?", Long.class,
                                productId)));
    }

    private void offerProduct(UUID store, UUID productId) {
        jdbc.update("insert into catalog.store_products (store_id, product_id, available, updated_at, version) "
                + "values (?,?,true,now(),0)", store, productId);
    }

    private UUID seedStore() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?, ?, 'Ops Branch', 'Addr', 'Asia/Phnom_Penh', 'USD', 'en-US', true, now(), now(), 0)",
                id, "OPS" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        return id;
    }

    private UUID seedCustomer() {
        UUID id = UUID.randomUUID();
        String email = "ops-it-" + id + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)", id, "Ops IT Customer", email, email, "en-US");
        jdbc.update("insert into customer.customer_profiles (customer_id, user_id, marketing_opt_in, created_at, "
                + "updated_at, version) values (?,?,false,now(),now(),0)", UUID.randomUUID(), id);
        return id;
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

    // ------------------------------------------------------- actor plumbing
    // The recipe/assignment services resolve the caller from the SecurityContext,
    // so seeding a global admin needs a real authenticated principal. A tiny
    // SUPER_ADMIN user (store-NULL assignment) is inserted and installed.

    private com.smartcoffee.common.security.CurrentActor globalActorForSeed() {
        return seedActor(SUPER_ADMIN);
    }

    protected com.smartcoffee.common.security.CurrentActor seedActor(String roleId) {
        UUID id = UUID.randomUUID();
        String email = "ops-it-admin-" + id + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)", id, "Ops IT Admin", email, email, "en-US");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, "
                + "assigned_at) values (?,?,?,?,?)", UUID.randomUUID(), id, UUID.fromString(roleId), null,
                java.sql.Timestamp.from(Instant.now()));
        org.springframework.security.core.context.SecurityContextHolder.getContext().setAuthentication(
                new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                        id.toString(), null, List.of()));
        return actors.require();
    }

    protected void clearActor() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    // ------------------------------------------------------------- utilities

    protected static RecipeItemRequest item(UUID ingredient, String qty, int order) {
        return new RecipeItemRequest(ingredient, dec(qty), BigDecimal.ZERO, null, RecipeScalingRule.FIXED, order,
                false, null);
    }

    protected static BigDecimal dec(String value) {
        return new BigDecimal(value);
    }

    protected static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
