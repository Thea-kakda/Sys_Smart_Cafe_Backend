package com.smartcoffee.modules.cart;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.cart.api.AddCartItemRequest;
import com.smartcoffee.modules.cart.api.CartItemResponse;
import com.smartcoffee.modules.cart.api.CartResponse;
import com.smartcoffee.modules.cart.api.ConfigureCartItemRequest;
import com.smartcoffee.modules.cart.api.SelectedCustomizationRequest;
import com.smartcoffee.modules.cart.application.CartExpirationService;
import com.smartcoffee.modules.cart.application.CartService;
import com.smartcoffee.modules.catalog.api.ProductVariantResponse;
import com.smartcoffee.modules.catalog.api.request.CreateProductVariantRequest;
import com.smartcoffee.modules.catalog.api.request.SetInventoryEffectRequest;
import com.smartcoffee.modules.catalog.application.OptionInventoryEffectCommandService;
import com.smartcoffee.modules.catalog.application.ProductVariantCommandService;
import com.smartcoffee.modules.catalog.domain.InventoryEffectType;
import com.smartcoffee.modules.recipe.api.RecipeVersionResponse;
import com.smartcoffee.modules.recipe.api.request.AssignProductRecipeRequest;
import com.smartcoffee.modules.recipe.api.request.AssignVariantRecipeRequest;
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
 * Slice 2C §19 — cart-time inventory reservation against a real migrated
 * PostgreSQL (Flyway V001..V045, real {@code ConsumptionPlanService}, real cart
 * commands, real balance guards and ledger).
 *
 * <p>Each test builds its own store, product, ingredients, active recipe and
 * customer, so nothing leaks between scenarios. Scenarios covered: A (add),
 * B (milk replacement), C (sugar reduction), D (extra shot), E (variant
 * change), F (insufficient stock), H (idempotent retry), I (remove + expiry),
 * J (branch isolation), plus clear, legacy/no-recipe lines and the
 * missing-balance rollback.
 *
 * <p>Every scenario additionally asserts the §21 no-consumption invariants:
 * no {@code SALE_CONSUMPTION} row and no {@code current_stock} decrease.
 */
class CartReservationIT extends IntegrationTestBase {

    private static final String SUPER_ADMIN = "00000000-0000-0000-0000-000000000002";
    private static final UUID GRAM = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final UUID MILLILITER = UUID.fromString("60000000-0000-0000-0000-000000000003");
    private static final UUID PIECE = UUID.fromString("60000000-0000-0000-0000-000000000005");
    private static final String STOCK_ON_HAND = "1000";

    @Autowired JdbcTemplate jdbc;
    @Autowired CurrentActorProvider actors;
    @Autowired CartService carts;
    @Autowired CartExpirationService expiry;
    @Autowired RecipeCommandService recipeCommands;
    @Autowired RecipeAssignmentService assignment;
    @Autowired ProductVariantCommandService variantCommands;
    @Autowired OptionInventoryEffectCommandService effects;

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

    // ------------------------------------------------------- Scenario A: add

    @Test
    void addReservesTheCompleteAuthoritativeDemand() {
        Latte l = latte();

        CartResponse cart = add(l, 1, null, List.of());

        assertEquals("ACTIVE", cart.status());
        assertEquals(storeId, cart.storeId());
        CartItemResponse item = onlyItem(cart);
        assertEquals(1, item.quantity());

        // Scenario A demand, exactly the 12 oz recipe, scaled by quantity 1.
        assertReserved(l.beans, "18");
        assertReserved(l.milk, "180");
        assertReserved(l.syrup, "20");
        assertReserved(l.ice, "120");
        assertReserved(l.cup, "1");
        assertReserved(l.lid, "1");

        // One ACTIVE cart-owned reservation carrying the persisted snapshot.
        assertEquals("ACTIVE", reservationColumn(item.cartItemId(), "status"));
        assertEquals("CART_ITEM", reservationColumn(item.cartItemId(), "source_type"));
        assertEquals("PRODUCT", reservationColumn(item.cartItemId(), "resolution_source"));
        assertEquals(1L, reservationRevision(item.cartItemId()));
        assertEquals(6, reservationLineCount(item.cartItemId()));
        assertEquals(0, snapshotQuantity(item.cartItemId(), l.beans).compareTo(dec("18")));
        assertEquals(0, snapshotQuantity(item.cartItemId(), l.milk).compareTo(dec("180")));
        assertNull(reservationColumn(item.cartItemId(), "order_id"), "cart rows carry no order");
        assertEquals(cart.cartId(), UUID.fromString(reservationColumn(item.cartItemId(), "cart_id")));

        // Ledger: exactly one SALE_RESERVATION header with one line per ingredient.
        assertEquals(1, ledgerHeaders(cart.cartId(), "SALE_RESERVATION"));
        assertEquals(6, ledgerLines(cart.cartId(), "SALE_RESERVATION"));
        assertEquals(0, ledgerDelta(cart.cartId(), "SALE_RESERVATION", l.beans).compareTo(dec("18")));
        assertEquals(0, ledgerDelta(cart.cartId(), "SALE_RESERVATION", l.cup).compareTo(dec("1")));
        assertEquals("CART_ITEM_ADDED", ledgerReason(cart.cartId(), "SALE_RESERVATION"));

        // §21: reservation only — nothing was consumed or taken off hand.
        assertOnHand(l.beans, STOCK_ON_HAND);
        assertNoConsumption();
    }

    // -------------------------------------- Scenario B: milk replacement (oat)

    @Test
    void milkReplacementReleasesFreshMilkAndReservesOat() {
        Latte l = latte();
        CartResponse cart = add(l, 1, null, List.of());
        UUID itemId = onlyItem(cart).cartItemId();
        OptionRef oat = effectOption(l.productId, "MILK", "SINGLE", 0, "oat",
                InventoryEffectType.REPLACE, l.milk, l.oat, null, null);

        CartResponse configured = carts.configure(userId, null, cart.cartId(), itemId,
                new ConfigureCartItemRequest(null, List.of(selection(oat))));

        assertEquals(1, onlyItem(configured).customizations().size(), "selection replaced atomically");
        assertReserved(l.milk, "0");
        assertReserved(l.oat, "180");
        // Untouched ingredients keep their hold and are absent from the delta.
        assertReserved(l.beans, "18");
        assertReserved(l.syrup, "20");
        assertReserved(l.ice, "120");
        assertEquals(0, ledgerDelta(cart.cartId(), "SALE_RELEASE", l.milk).compareTo(dec("-180")));
        assertEquals(0, ledgerDelta(cart.cartId(), "SALE_RESERVATION", l.oat).compareTo(dec("180")));
        assertNoConsumption();
    }

    // ------------------------------------------- Scenario C: sugar 100% -> 50%

    @Test
    void sugarReductionReleasesOnlyTheDifference() {
        Latte l = latte();
        CartResponse cart = add(l, 1, null, List.of());
        UUID itemId = onlyItem(cart).cartItemId();
        OptionRef sugar50 = effectOption(l.productId, "SUGAR", "SINGLE", 0, "sugar-50",
                InventoryEffectType.MULTIPLY, l.syrup, null, null, dec("0.50"));

        carts.configure(userId, null, cart.cartId(), itemId,
                new ConfigureCartItemRequest(null, List.of(selection(sugar50))));

        assertReserved(l.syrup, "10");
        // Exactly one release delta for the 10 ml difference — no full
        // release/re-reserve cycle, and no unrelated ingredient moved.
        assertEquals(0, ledgerDelta(cart.cartId(), "SALE_RELEASE", l.syrup).compareTo(dec("-10")));
        assertEquals(1, ledgerLines(cart.cartId(), "SALE_RELEASE"), "only sugar moved");
        assertEquals(0, ledgerDelta(cart.cartId(), "SALE_RELEASE", l.milk).compareTo(BigDecimal.ZERO));
        assertReserved(l.beans, "18");
        assertReserved(l.milk, "180");
        assertReserved(l.cup, "1");
        assertNoConsumption();
    }

    // ------------------------------------------------ Scenario D: extra shot

    @Test
    void extraShotReservesOnlyTheAdditionalBeans() {
        Latte l = latte();
        CartResponse cart = add(l, 2, null, List.of());
        UUID itemId = onlyItem(cart).cartItemId();
        assertReserved(l.beans, "36");
        OptionRef extra = effectOption(l.productId, "EXTRAS", "MULTIPLE", 0, "extra-shot",
                InventoryEffectType.ADD, null, l.beans, dec("9"), null);

        carts.configure(userId, null, cart.cartId(), itemId,
                new ConfigureCartItemRequest(null, List.of(selection(extra))));

        // (18 + 9) * 2 = 54: the extra shot is scaled by the line quantity once.
        assertReserved(l.beans, "54");
        assertEquals(0, lastLedgerDelta(cart.cartId(), "SALE_RESERVATION", l.beans).compareTo(dec("18")));
        assertReserved(l.milk, "360");
        assertNoConsumption();
    }

    // ---------------------------------------------- Scenario E: variant change

    @Test
    void variantChangeSwapsTheCupAndScalesTheRecipe() {
        Latte l = latte();
        UUID cup16 = seedIngredient("CUP16_" + suffix(), PIECE);
        seedBalance(cup16, STOCK_ON_HAND);
        UUID largeVariant = largeVariant(l, cup16);

        // 12 oz (product fallback) first.
        CartResponse cart = add(l, 1, null, List.of());
        UUID itemId = onlyItem(cart).cartItemId();
        assertReserved(l.cup, "1");
        assertReserved(cup16, "0");

        carts.configure(userId, null, cart.cartId(), itemId,
                new ConfigureCartItemRequest(largeVariant, List.of()));

        // Product fallback vs variant override: the 16 oz recipe wins for the
        // configured variant, and only the difference moves.
        assertEquals("VARIANT", reservationColumn(itemId, "resolution_source"));
        assertReserved(cup16, "1");
        assertReserved(l.cup, "0");
        assertReserved(l.beans, "24");
        assertReserved(l.milk, "240");
        assertEquals(0, ledgerDelta(cart.cartId(), "SALE_RELEASE", l.cup).compareTo(dec("-1")));
        assertEquals(0, ledgerDelta(cart.cartId(), "SALE_RESERVATION", cup16).compareTo(dec("1")));
        assertEquals(2L, reservationRevision(itemId), "one coherent revision, not a duplicate row");
        assertEquals(1, reservationRowCount(itemId));
        assertNoConsumption();
    }

    // -------------------------------------------- Scenario F: insufficient stock

    @Test
    void insufficientStockRejectsTheWholeAddAndReservesNothing() {
        Latte l = latte();
        setOnHand(l.milk, "100"); // 180 ml needed

        ConflictException ex = assertThrows(ConflictException.class, () -> add(l, 1, null, List.of()));
        assertEquals("INSUFFICIENT_STOCK", ex.code());
        assertTrue(ex.getMessage().contains("available 100"), ex.getMessage());

        // No partial reservation: every ingredient is back at zero held, the cart
        // never got a line, and no ledger row was written.
        assertReserved(l.beans, "0");
        assertReserved(l.milk, "0");
        assertReserved(l.syrup, "0");
        assertReserved(l.ice, "0");
        assertReserved(l.cup, "0");
        assertReserved(l.lid, "0");
        assertEquals(0, cartItemCount(), "the cart line is not persisted");
        assertEquals(0, reservationCount(), "no reservation row at all");
        assertEquals(0, ledgerHeadersAny("SALE_RESERVATION"));
        assertNoConsumption();
    }

    @Test
    void missingBalanceIsRejectedRollingBackEarlierIngredients() {
        Latte l = latte();
        jdbc.update("delete from inventory.inventory_balances where store_id=? and ingredient_id=?",
                storeId, l.milk);

        ConflictException ex = assertThrows(ConflictException.class, () -> add(l, 1, null, List.of()));
        assertEquals("INVENTORY_BALANCE_NOT_FOUND", ex.code());

        // Ingredients visited before the failure must be rolled back.
        assertReserved(l.beans, "0");
        assertReserved(l.cup, "0");
        assertEquals(0, reservationCount());
        assertEquals(0, ledgerHeadersAny("SALE_RESERVATION"));
        assertNoConsumption();
    }

    // --------------------------------------------- Scenario H: idempotent retry

    @Test
    void idempotentRetryReplaysWithoutASecondReservation() {
        Latte l = latte();
        UUID key = UUID.randomUUID();
        AddCartItemRequest request = new AddCartItemRequest(storeId, l.productId(), null, 1, null, List.of());

        CartResponse first = carts.add(userId, key, request);
        CartResponse second = carts.add(userId, key, request);

        assertEquals(first.cartId(), second.cartId());
        assertEquals(1, second.items().size(), "same cart, still one line");
        assertEquals(0, reserved(l.beans).compareTo(dec("18")), "both components hold 18 g, not 36 g");
        assertEquals(1L, reservationRevision(onlyItem(first).cartItemId()));
        assertEquals(1, ledgerHeaders(first.cartId(), "SALE_RESERVATION"));
        assertEquals(6, ledgerLines(first.cartId(), "SALE_RESERVATION"));

        // Same key, different payload: rejected without touching state.
        AddCartItemRequest different = new AddCartItemRequest(storeId, l.productId(), null, 3, null, List.of());
        ConflictException reused = assertThrows(ConflictException.class,
                () -> carts.add(userId, key, different));
        assertEquals("IDEMPOTENCY_KEY_REUSED", reused.code());
        assertEquals(0, reserved(l.beans).compareTo(dec("18")));
        assertNoConsumption();
    }

    @Test
    void quantityChangesReserveAndReleaseOnlyTheDelta() {
        Latte l = latte();
        CartResponse cart = add(l, 1, null, List.of());
        UUID itemId = onlyItem(cart).cartItemId();

        CartResponse up = carts.updateQuantity(userId, null, cart.cartId(), itemId, 3);
        assertEquals(3, onlyItem(up).quantity());
        assertReserved(l.beans, "54");
        assertEquals(0, lastLedgerDelta(cart.cartId(), "SALE_RESERVATION", l.beans).compareTo(dec("36")));
        assertEquals(2L, reservationRevision(itemId));

        CartResponse down = carts.updateQuantity(userId, null, cart.cartId(), itemId, 1);
        assertEquals(1, onlyItem(down).quantity());
        assertReserved(l.beans, "18");
        assertEquals(0, lastLedgerDelta(cart.cartId(), "SALE_RELEASE", l.beans).compareTo(dec("-36")));
        assertEquals(3L, reservationRevision(itemId));
        assertNoConsumption();
    }

    // ------------------------------------------ Scenario I: remove and expiry

    @Test
    void removeReleasesEverythingOnce() {
        Latte l = latte();
        CartResponse cart = add(l, 1, null, List.of());
        UUID itemId = onlyItem(cart).cartItemId();
        UUID reservationId = reservationId(itemId);

        CartResponse after = carts.remove(userId, cart.cartId(), itemId);

        assertTrue(after.items().isEmpty());
        assertReserved(l.beans, "0");
        assertReserved(l.milk, "0");
        assertReserved(l.cup, "0");
        assertEquals("RELEASED", reservationColumn(itemId, "status"));
        assertEquals("CART_ITEM_REMOVED", reservationColumn(itemId, "release_reason"));
        assertNotNull(reservationColumn(itemId, "released_at"));
        assertEquals(1, ledgerHeaders(cart.cartId(), "SALE_RELEASE"));
        assertEquals(6, ledgerLines(cart.cartId(), "SALE_RELEASE"));
        // The released audit row survives the cart line's hard delete.
        assertEquals(1L, jdbc.queryForObject(
                "select count(*) from inventory.inventory_reservations where reservation_id=?", Long.class,
                reservationId));

        // Replaying the removal must not release twice.
        assertThrows(ConflictException.class, () -> carts.remove(userId, cart.cartId(), itemId));
        assertEquals(1, ledgerHeaders(cart.cartId(), "SALE_RELEASE"));
        assertReserved(l.beans, "0");
        assertNoConsumption();
    }

    @Test
    void expiryReleasesEverythingExactlyOnce() {
        Latte l = latte();
        CartResponse cart = add(l, 1, null, List.of());
        UUID itemId = onlyItem(cart).cartItemId();
        backdateCart(cart.cartId());

        assertTrue(expiry.expireCart(cart.cartId()), "the cart is expired by the worker");
        assertEquals("EXPIRED", jdbc.queryForObject("select status from cart.carts where cart_id=?",
                String.class, cart.cartId()));
        assertEquals("RELEASED", reservationColumn(itemId, "status"));
        assertEquals("CART_EXPIRED", reservationColumn(itemId, "release_reason"));
        assertReserved(l.beans, "0");
        assertReserved(l.cup, "0");
        assertEquals(1, ledgerHeaders(cart.cartId(), "SALE_RELEASE"));
        assertEquals("CART_EXPIRED", ledgerReason(cart.cartId(), "SALE_RELEASE"));

        // A second sweep (or a second worker) is a no-op.
        assertFalse(expiry.expireCart(cart.cartId()));
        assertEquals(1, ledgerHeaders(cart.cartId(), "SALE_RELEASE"));
        assertReserved(l.beans, "0");
        assertOnHand(l.beans, STOCK_ON_HAND);
        assertNoConsumption();
    }

    @Test
    void clearReleasesEveryHeldLine() {
        Latte l = latte();
        CartResponse cart = add(l, 1, null, List.of());
        add(l, 1, null, List.of()); // same cart, second line

        CartResponse cleared = carts.clear(userId, cart.cartId());

        assertTrue(cleared.items().isEmpty());
        assertReserved(l.beans, "0");
        assertReserved(l.milk, "0");
        assertEquals("RELEASED", jdbc.queryForObject("select status from inventory.inventory_reservations "
                + "where cart_id=? and cart_item_id is not null order by created_at limit 1", String.class,
                cart.cartId()));
        assertTrue(ledgerHeaders(cart.cartId(), "SALE_RELEASE") >= 1);
        // Clearing again is safe and releases nothing further.
        long before = ledgerHeaders(cart.cartId(), "SALE_RELEASE");
        carts.clear(userId, cart.cartId());
        assertEquals(before, ledgerHeaders(cart.cartId(), "SALE_RELEASE"));
        assertReserved(l.beans, "0");
        assertNoConsumption();
    }

    // ------------------------------------------- Scenario J: branch isolation

    @Test
    void aStoreWithoutBalancesCannotReserveAnotherStoresStock() {
        Latte l = latte(); // balances exist in storeId only
        UUID otherStore = seedStore();
        offerProduct(otherStore, l.productId);

        ConflictException ex = assertThrows(ConflictException.class, () -> carts.add(userId, null,
                new AddCartItemRequest(otherStore, l.productId(), null, 1, null, List.of())));
        assertEquals("INVENTORY_BALANCE_NOT_FOUND", ex.code());

        // The store that does have stock was never touched.
        assertReserved(l.beans, "0");
        assertReserved(l.milk, "0");
        assertEquals(0, reservationCount());
    }

    @Test
    void aStoreWithZeroStockCannotReserveAnything() {
        Latte l = latte();
        UUID otherStore = seedStore();
        offerProduct(otherStore, l.productId);
        for (UUID ingredient : List.of(l.beans, l.milk, l.syrup, l.ice, l.cup, l.lid)) {
            jdbc.update("insert into inventory.inventory_balances (store_id, ingredient_id, current_stock, "
                    + "reserved_stock, minimum_stock, average_unit_cost) values (?,?,0,0,0,0)", otherStore,
                    ingredient);
        }

        ConflictException ex = assertThrows(ConflictException.class, () -> carts.add(userId, null,
                new AddCartItemRequest(otherStore, l.productId(), null, 1, null, List.of())));
        assertEquals("INSUFFICIENT_STOCK", ex.code());

        assertEquals(0, reserved(l.beans).compareTo(dec("0")), "own store untouched");
        assertEquals(0, jdbc.queryForObject("select coalesce(sum(reserved_stock),0) from "
                + "inventory.inventory_balances where store_id=?", BigDecimal.class, otherStore)
                .compareTo(dec("0")));
        assertNoConsumption();
    }

    // ------------------------------------- legacy / untracked / invalid input

    @Test
    void aProductWithoutARecipeCreatesNoReservation() {
        UUID productId = seedProduct("MTO_" + suffix());
        offerProduct(storeId, productId);

        CartResponse cart = carts.add(userId, null,
                new AddCartItemRequest(storeId, productId, null, 1, null, List.of()));

        assertEquals(1, cart.items().size(), "the line is still sellable");
        UUID itemId = onlyItem(cart).cartItemId();
        assertEquals(0L, jdbc.queryForObject("select count(*) from inventory.inventory_reservations "
                + "where cart_item_id=?", Long.class, itemId), "no demand, no hold");
        assertEquals(0, ledgerHeaders(cart.cartId(), "SALE_RESERVATION"));
        assertNoConsumption();
    }

    @Test
    void anUntrackedProductCreatesNoReservation() {
        UUID productId = seedProduct("UNTRACKED_" + suffix());
        jdbc.update("update catalog.products set stock_mode='NOT_TRACKED' where product_id=?", productId);
        offerProduct(storeId, productId);

        CartResponse cart = carts.add(userId, null,
                new AddCartItemRequest(storeId, productId, null, 2, null, List.of()));

        UUID itemId = onlyItem(cart).cartItemId();
        assertEquals(0L, jdbc.queryForObject("select count(*) from inventory.inventory_reservations "
                + "where cart_item_id=?", Long.class, itemId));
        assertEquals(0, ledgerHeaders(cart.cartId(), "SALE_RESERVATION"));
        assertNoConsumption();
    }

    @Test
    void anInvalidCustomizationSelectionReservesNothing() {
        Latte l = latte();
        UUID groupId = seedGroup("SIZE", "SINGLE", 1, 1);
        seedOption(groupId, "reg_" + suffix(), "Regular");
        linkGroup(l.productId, groupId);

        ValidationException ex = assertThrows(ValidationException.class, () -> add(l, 1, null, List.of()));
        assertTrue(ex.getMessage().contains("Required customization"), ex.getMessage());
        assertReserved(l.beans, "0");
        assertEquals(0, reservationCount());
        assertEquals(0, cartItemCount());
        assertNoConsumption();
    }

    // ------------------------------------------------------------- fixtures

    private record Latte(UUID productId, UUID beans, UUID milk, UUID syrup, UUID oat, UUID ice, UUID cup, UUID lid,
            UUID recipeVersionId) {
    }

    private record OptionRef(UUID groupId, UUID optionId) {
    }

    private Latte latte() {
        UUID productId = seedProduct("LATTE_" + suffix());
        offerProduct(storeId, productId);
        UUID beans = seedIngredient("BEANS_" + suffix(), GRAM);
        UUID milk = seedIngredient("MILK_" + suffix(), MILLILITER);
        UUID syrup = seedIngredient("SYRUP_" + suffix(), MILLILITER);
        UUID oat = seedIngredient("OAT_" + suffix(), MILLILITER);
        UUID ice = seedIngredient("ICE_" + suffix(), GRAM);
        UUID cup = seedIngredient("CUP12_" + suffix(), PIECE);
        UUID lid = seedIngredient("LID12_" + suffix(), PIECE);
        for (UUID ingredient : List.of(beans, milk, syrup, oat, ice, cup, lid)) {
            seedBalance(ingredient, STOCK_ON_HAND);
        }

        List<RecipeItemRequest> items = List.of(
                item(beans, "18", 0), item(milk, "180", 1), item(syrup, "20", 2),
                item(ice, "120", 3), item(cup, "1", 4), item(lid, "1", 5));
        RecipeVersionResponse draft = recipeCommands.createDraft(admin, productId,
                new CreateRecipeDraftRequest(BigDecimal.ONE, null, "latte 12oz", items));
        RecipeVersionResponse active = recipeCommands.activate(admin, draft.recipeVersionId(), draft.version());
        assignment.assignProduct(admin, productId,
                new AssignProductRecipeRequest(active.recipeVersionId(), productVersion(productId)));
        return new Latte(productId, beans, milk, syrup, oat, ice, cup, lid, active.recipeVersionId());
    }

    /** 16 oz variant with its own ACTIVE recipe (beans 24, milk 240, its own cup). */
    private UUID largeVariant(Latte l, UUID cup16) {
        ProductVariantResponse variant = variantCommands.create(admin, l.productId(),
                new CreateProductVariantRequest("LARGE_" + suffix(), "Large", null, null, dec("14"), null, "USD",
                        false, true, 0, null));
        UUID variantId = variant.variantId();
        List<RecipeItemRequest> items = List.of(
                item(l.beans, "24", 0), item(l.milk, "240", 1), item(l.syrup, "30", 2),
                item(l.ice, "160", 3), item(cup16, "1", 4));
        RecipeVersionResponse draft = recipeCommands.createDraft(admin, l.productId(),
                new CreateRecipeDraftRequest(BigDecimal.ONE, null, "latte 16oz", items));
        RecipeVersionResponse active = recipeCommands.activate(admin, draft.recipeVersionId(), draft.version());
        assignment.assign(admin, variantId, new AssignVariantRecipeRequest(active.recipeVersionId(),
                variantVersion(variantId)));
        return variantId;
    }

    private OptionRef effectOption(UUID productId, String groupCode, String selectionType, int min,
            String optionCode, InventoryEffectType type, UUID source, UUID target, BigDecimal qty, BigDecimal mult) {
        UUID groupId = seedGroup(groupCode, selectionType, min, null);
        UUID optionId = seedOption(groupId, optionCode + "_" + suffix(), optionCode);
        linkGroup(productId, groupId);
        effects.set(admin, optionId, null, new SetInventoryEffectRequest(type, source, target, qty, mult, null));
        return new OptionRef(groupId, optionId);
    }

    private static SelectedCustomizationRequest selection(OptionRef ref) {
        return new SelectedCustomizationRequest(ref.groupId(), ref.optionId(), 1);
    }

    private CartResponse add(Latte l, int quantity, UUID variantId, List<SelectedCustomizationRequest> selections) {
        return carts.add(userId, null,
                new AddCartItemRequest(storeId, l.productId(), variantId, quantity, null, selections));
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
                "current_stock for " + ingredient + " must never move in Slice 2C");
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

    private long ledgerHeaders(UUID cartId, String type) {
        return jdbc.queryForObject("select count(*) from inventory.stock_transactions "
                + "where cart_id=? and transaction_type=?", Long.class, cartId, type);
    }

    private long ledgerHeadersAny(String type) {
        return jdbc.queryForObject("select count(*) from inventory.stock_transactions "
                + "where transaction_type=? and store_id=?", Long.class, type, storeId);
    }

    private long ledgerLines(UUID cartId, String type) {
        return jdbc.queryForObject("select count(*) from inventory.stock_transaction_lines l "
                + "join inventory.stock_transactions t on t.stock_transaction_id=l.stock_transaction_id "
                + "where t.cart_id=? and t.transaction_type=?", Long.class, cartId, type);
    }

    private BigDecimal ledgerDelta(UUID cartId, String type, UUID ingredient) {
        return jdbc.queryForObject("select coalesce(sum(l.quantity_delta),0) from "
                + "inventory.stock_transaction_lines l join inventory.stock_transactions t "
                + "on t.stock_transaction_id=l.stock_transaction_id "
                + "where t.cart_id=? and t.transaction_type=? and l.ingredient_id=?", BigDecimal.class, cartId,
                type, ingredient);
    }

    /**
     * The delta carried by the MOST RECENT ledger header of a type — this is what
     * proves a change moved only its own difference instead of re-reserving.
     */
    private BigDecimal lastLedgerDelta(UUID cartId, String type, UUID ingredient) {
        return jdbc.queryForObject("select l.quantity_delta from inventory.stock_transaction_lines l "
                + "join inventory.stock_transactions t on t.stock_transaction_id=l.stock_transaction_id "
                + "where t.cart_id=? and t.transaction_type=? and l.ingredient_id=? "
                + "order by t.occurred_at desc, t.stock_transaction_id desc limit 1", BigDecimal.class, cartId,
                type, ingredient);
    }

    private String ledgerReason(UUID cartId, String type) {
        return jdbc.queryForObject("select reason from inventory.stock_transactions where cart_id=? "
                + "and transaction_type=? order by occurred_at limit 1", String.class, cartId, type);
    }

    private UUID reservationId(UUID cartItemId) {
        return jdbc.queryForObject("select reservation_id from inventory.inventory_reservations "
                + "where cart_item_id=?", UUID.class, cartItemId);
    }

    private String reservationColumn(UUID cartItemId, String column) {
        return jdbc.queryForObject("select cast(" + column + " as text) from inventory.inventory_reservations "
                + "where cart_item_id=?", String.class, cartItemId);
    }

    private long reservationRevision(UUID cartItemId) {
        return jdbc.queryForObject("select demand_revision from inventory.inventory_reservations "
                + "where cart_item_id=?", Long.class, cartItemId);
    }

    private long reservationRowCount(UUID cartItemId) {
        return jdbc.queryForObject("select count(*) from inventory.inventory_reservations where cart_item_id=?",
                Long.class, cartItemId);
    }

    private long reservationLineCount(UUID cartItemId) {
        return jdbc.queryForObject("select count(*) from inventory.inventory_reservation_lines "
                + "where reservation_id=?", Long.class, reservationId(cartItemId));
    }

    private BigDecimal snapshotQuantity(UUID cartItemId, UUID ingredient) {
        return jdbc.queryForObject("select quantity_reserved from inventory.inventory_reservation_lines "
                + "where reservation_id=? and ingredient_id=?", BigDecimal.class, reservationId(cartItemId),
                ingredient);
    }

    private long reservationCount() {
        return jdbc.queryForObject("select count(*) from inventory.inventory_reservations where store_id=? "
                + "and cart_item_id is not null", Long.class, storeId);
    }

    private long cartItemCount() {
        return jdbc.queryForObject("select count(*) from cart.cart_items i join cart.carts c "
                + "on c.cart_id=i.cart_id where c.store_id=?", Long.class, storeId);
    }

    // --------------------------------------------------------- raw seeding

    /** Makes the cart look elapsed without tripping cart.carts' ck_cart_expiry guard. */
    private void backdateCart(UUID cartId) {
        jdbc.update("update cart.carts set created_at = now() - interval '3 days', "
                + "expires_at = now() - interval '1 day' where cart_id=?", cartId);
    }

    private void setOnHand(UUID ingredient, String current) {
        jdbc.update("update inventory.inventory_balances set current_stock=? where store_id=? and ingredient_id=?",
                dec(current), storeId, ingredient);
    }

    private UUID seedStore() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?, ?, 'Cart IT Branch', 'Addr', 'Asia/Phnom_Penh', 'USD', 'en-US', true, now(), now(), 0)",
                id, "CIT" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        return id;
    }

    private UUID seedCustomer() {
        UUID id = UUID.randomUUID();
        String email = "cart-it-" + id + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)", id, "Cart IT Customer", email, email, "en-US");
        jdbc.update("insert into customer.customer_profiles (customer_id, user_id, marketing_opt_in, created_at, "
                + "updated_at, version) values (?,?,false,now(),now(),0)", UUID.randomUUID(), id);
        return id;
    }

    private CurrentActor seedActor(String roleId) {
        UUID id = UUID.randomUUID();
        String email = "cart-it-admin-" + id + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)", id, "Cart IT Admin", email, email, "en-US");
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

    private UUID seedGroup(String code, String selectionType, int min, Integer max) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into catalog.customization_groups (group_id, group_code, group_name, selection_type, "
                + "minimum_selections, maximum_selections, display_order, active, created_at, updated_at, version) "
                + "values (?,?,?,?,?,?,1,true,now(),now(),0)", id, code + "_" + suffix(), code, selectionType, min,
                max);
        return id;
    }

    private UUID seedOption(UUID groupId, String code, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into catalog.customization_options (option_id, group_id, option_code, option_name, "
                + "additional_price, display_order, active, created_at, updated_at, version) "
                + "values (?,?,?,?,0,0,true,now(),now(),0)", id, groupId, code, name);
        return id;
    }

    private void linkGroup(UUID productId, UUID groupId) {
        jdbc.update("insert into catalog.product_customization_groups (product_id, group_id, required, display_order) "
                + "values (?,?,false,0)", productId, groupId);
    }

    private long productVersion(UUID productId) {
        return jdbc.queryForObject("select version from catalog.products where product_id=?", Long.class, productId);
    }

    private long variantVersion(UUID variantId) {
        return jdbc.queryForObject("select version from catalog.product_variants where variant_id=?", Long.class,
                variantId);
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
