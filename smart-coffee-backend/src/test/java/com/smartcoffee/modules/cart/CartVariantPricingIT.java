package com.smartcoffee.modules.cart;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.exception.BusinessException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.cart.api.AddCartItemRequest;
import com.smartcoffee.modules.cart.api.CartItemResponse;
import com.smartcoffee.modules.cart.api.CartResponse;
import com.smartcoffee.modules.cart.api.SelectedCustomizationRequest;
import com.smartcoffee.modules.cart.application.CartService;
import com.smartcoffee.modules.ordering.api.CreateOrderRequest;
import com.smartcoffee.modules.ordering.api.OrderResponse;
import com.smartcoffee.modules.ordering.application.OrderCheckoutService;
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
 * Slice 4B.1 — Cart variant pricing and variant identity.
 *
 * <p>Reproduces and closes the live-smoke defect: when a sellable variant is
 * selected, the authoritative cart line base price is the VARIANT price (it
 * REPLACES the product/store base, never adds to it), option deltas add on top,
 * quantity multiplies the unit total, and the line carries a persisted variant
 * identity that survives into the converted order. An invalid variant is
 * rejected with a stable error rather than silently repricing. Reservation
 * demand (already correct) must not move.
 *
 * <p>Scenario fixtures mirror the smoke product: Smoke-Latte base 3.50, Large
 * 16 oz variant 4.50, an optional Extra Shot option +0.10. Expected
 * authoritative unit total = 4.50 + 0.10 = 4.60.
 */
class CartVariantPricingIT extends IntegrationTestBase {

    private static final String SUPER_ADMIN = "00000000-0000-0000-0000-000000000002";
    private static final UUID GRAM = UUID.fromString("60000000-0000-0000-0000-000000000001");
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

    // ------------------------------------------------------- Scenario A: variant wins

    @Test
    void selectedVariantDrivesTheAuthoritativeUnitPrice() {
        UUID productId = seededLatteProduct("3.50");
        UUID variantId = variant(productId, "Large 16 oz", "4.50");
        UUID extraGroupId = seedGroup("EXTRAS", "MULTIPLE", 0);
        UUID extraOptionId = seedPricedOption(extraGroupId, "Extra Shot", "0.10");
        linkGroup(productId, extraGroupId);

        CartResponse cart = carts.add(userId, null, new AddCartItemRequest(storeId, productId, variantId, 1, null,
                List.of(new SelectedCustomizationRequest(extraGroupId, extraOptionId, 1))));

        CartItemResponse item = onlyItem(cart);
        assertEquals(0, dec("4.50").compareTo(item.unitBasePrice()),
                "cart line base must be the variant price (4.50), not the product base (3.50)");
        assertEquals(0, dec("0.10").compareTo(item.unitCustomizationPrice()),
                "Extra Shot delta still applies on top of the variant price");
        assertEquals(0, dec("4.60").compareTo(item.lineTotal()), "authoritative line total = variant 4.50 + option 0.10");
    }

    // ----------------------------------------- Scenario B: variant priced at base

    @Test
    void regularVariantPricedAtTheProductBase() {
        UUID productId = seededLatteProduct("3.50");
        UUID variantId = variant(productId, "Regular 12 oz", "3.50");

        CartResponse cart = carts.add(userId, null,
                new AddCartItemRequest(storeId, productId, variantId, 1, null, List.of()));

        assertEquals(0, dec("3.50").compareTo(onlyItem(cart).unitBasePrice()));
        assertEquals(0, dec("3.50").compareTo(onlyItem(cart).lineTotal()));
    }

    // ----------------------- Scenario C: store override preserved for no-variant

    @Test
    void storeOverrideStillAppliesWhenNoVariantIsSelected() {
        UUID productId = seededLatteProduct("3.50");
        jdbc.update("update catalog.store_products set override_price=? where store_id=? and product_id=?",
                dec("3.90"), storeId, productId);

        CartResponse cart = carts.add(userId, null,
                new AddCartItemRequest(storeId, productId, null, 1, null, List.of()));

        CartItemResponse item = onlyItem(cart);
        assertEquals(0, dec("3.90").compareTo(item.unitBasePrice()), "no-variant override path is unchanged");
        assertNull(item.variantId());
        assertNull(item.variantName());
    }

    // -------------------------------------------------- Scenario D: quantity scales

    @Test
    void quantityScalesTheAuthoritativeUnitPrice() {
        UUID productId = seededLatteProduct("3.50");
        UUID variantId = variant(productId, "Large 16 oz", "4.50");
        UUID extraGroupId = seedGroup("EXTRAS", "MULTIPLE", 0);
        UUID extraOptionId = seedPricedOption(extraGroupId, "Extra Shot", "0.10");
        linkGroup(productId, extraGroupId);

        CartResponse cart = carts.add(userId, null, new AddCartItemRequest(storeId, productId, variantId, 2, null,
                List.of(new SelectedCustomizationRequest(extraGroupId, extraOptionId, 1))));

        assertEquals(0, dec("9.20").compareTo(onlyItem(cart).lineTotal()), "(4.50 + 0.10) * 2");
    }

    // ------------------------ Scenario E: later variant price edit cannot rewrite cart

    @Test
    void variantPriceEditAfterAddLeavesTheCartSnapshotUnchanged() {
        UUID productId = seededLatteProduct("3.50");
        UUID variantId = variant(productId, "Large 16 oz", "4.50");
        UUID cartId = carts.add(userId, null,
                new AddCartItemRequest(storeId, productId, variantId, 1, null, List.of())).cartId();

        // Retire the price and rename the variant in the catalog AFTER the add.
        jdbc.update("update catalog.product_variants set price=?, variant_name=? where variant_id=?",
                dec("9.99"), "Large 20 oz", variantId);

        CartResponse reloaded = carts.current(userId, storeId);
        assertEquals(cartId, reloaded.cartId());
        CartItemResponse item = onlyItem(reloaded);
        assertEquals(0, dec("4.50").compareTo(item.unitBasePrice()), "historical cart total is frozen at 4.50");
        assertEquals("Large 16 oz", item.variantName(), "the sold label is the snapshot, not the live catalog row");
        assertEquals(0, dec("4.50").compareTo(item.variantUnitPrice()));
    }

    // ------------------------- Scenario F: checkout carries identity + authoritative price

    @Test
    void checkoutCarriesVariantIdentityAndAuthoritativePriceIntoTheOrder() {
        UUID productId = seededLatteProduct("3.50");
        UUID variantId = variant(productId, "Large 16 oz", "4.50");
        UUID extraGroupId = seedGroup("EXTRAS", "MULTIPLE", 0);
        UUID extraOptionId = seedPricedOption(extraGroupId, "Extra Shot", "0.10");
        linkGroup(productId, extraGroupId);

        CartResponse cart = carts.add(userId, null, new AddCartItemRequest(storeId, productId, variantId, 1, null,
                List.of(new SelectedCustomizationRequest(extraGroupId, extraOptionId, 1))));

        OrderResponse order = checkouts.checkout(userId, UUID.randomUUID(),
                new CreateOrderRequest(cart.cartId(), "PICKUP", null));

        var line = jdbc.queryForMap("select variant_id, variant_code_snapshot, variant_name_snapshot, "
                + "variant_price_snapshot, unit_base_price, unit_customization_price from ordering.order_items "
                + "where order_id=?", order.orderId());
        assertEquals(variantId, UUID.fromString(String.valueOf(line.get("variant_id"))), "OrderItem.variant_id reused");
        assertEquals("Large 16 oz", line.get("variant_name_snapshot"), "variant identity survives checkout");
        assertEquals(0, dec("4.50").compareTo((BigDecimal) line.get("variant_price_snapshot")));
        assertEquals(0, dec("4.50").compareTo((BigDecimal) line.get("unit_base_price")),
                "the order line uses the same authoritative cart base (variant price), not the product base");
        assertEquals(0, dec("0.10").compareTo((BigDecimal) line.get("unit_customization_price")));
    }

    // ----------------------------------------------- Cart response exposes identity

    @Test
    void cartResponseCarriesVariantIdentityFields() {
        UUID productId = seededLatteProduct("3.50");
        UUID variantId = variant(productId, "Large 16 oz", "4.50");

        CartItemResponse item = onlyItem(carts.add(userId, null,
                new AddCartItemRequest(storeId, productId, variantId, 1, null, List.of())));

        assertEquals(variantId, item.variantId());
        assertNotNull(item.variantCode());
        assertEquals("Large 16 oz", item.variantName());
        assertEquals(0, dec("4.50").compareTo(item.variantUnitPrice()));
    }

    // -------------------------------------- Validation: stable errors, no fallback

    @Test
    void invalidVariantReferencesAreRejectedWithStableErrors() {
        UUID productId = seededLatteProduct("3.50");
        UUID foreign = seededLatteProduct("3.50");
        UUID foreignVariant = variant(foreign, "Other Product Size", "5.00");
        UUID inactiveVariant = variantRaw(productId, "OLD_SIZE", "Discontinued", "4.50", "USD", false);
        UUID eurVariant = variantRaw(productId, "EUR_SIZE", "Euro Size", "4.50", "EUR", true);

        assertEquals("VARIANT_NOT_FOUND", addError(productId, UUID.randomUUID()));
        assertEquals("VARIANT_PRODUCT_MISMATCH", addError(productId, foreignVariant));
        assertEquals("VARIANT_INACTIVE", addError(productId, inactiveVariant));
        assertEquals("CURRENCY_MISMATCH", addError(productId, eurVariant));
    }

    @Test
    void rejectedVariantDoesNotCreateACartLine() {
        UUID productId = seededLatteProduct("3.50");
        UUID foreign = seededLatteProduct("3.50");
        UUID foreignVariant = variant(foreign, "Other Product Size", "5.00");

        assertThrows(BusinessException.class, () -> carts.add(userId, null,
                new AddCartItemRequest(storeId, productId, foreignVariant, 1, null, List.of())));
        assertEquals(0, cartItemCount(), "a rejected variant must never fall back to the product base and add");
    }

    // -------------------------------- Reservation non-regression (Slices prove it holds)

    @Test
    void variantPricingDoesNotDisturbReservationDemand() {
        ReservedLatte l = reservedLatte();
        UUID variantId = l.variantId();

        CartResponse cart = carts.add(userId, null,
                new AddCartItemRequest(storeId, l.productId(), variantId, 1, null, List.of()));

        // Pricing is now variant-authoritative...
        assertEquals(0, dec("14.00").compareTo(onlyItem(cart).unitBasePrice()));
        // ...but the reserved DEMAND is unchanged: the 16 oz recipe (beans 24,
        // its own cup) still drives the hold, resolution_source stays VARIANT.
        assertEquals("VARIANT", reservationColumn(onlyItem(cart).cartItemId(), "resolution_source"));
        assertReserved(l.beans16, "24");
        assertReserved(l.cup16, "1");
        assertOnHand(l.beans16, "1000");
        assertOnHand(l.cup16, "1000");
        assertNoConsumption();
    }

    // ---------------------------------------------------- Migration: V050 columns exist

    @Test
    void v050AddsNullableVariantSnapshotColumns() {
        for (String pair : new String[] { "cart|cart_items", "ordering|order_items" }) {
            String schema = pair.split("\\|")[0];
            String table = pair.split("\\|")[1];
            for (String column : new String[] { "variant_code_snapshot", "variant_name_snapshot",
                    "variant_price_snapshot" }) {
                Long present = jdbc.queryForObject("select count(*) from information_schema.columns "
                        + "where table_schema=? and table_name=? and column_name=?", Long.class, schema, table, column);
                assertEquals(1L, present, schema + "." + table + "." + column + " must exist (V050)");
                String nullable = jdbc.queryForObject("select is_nullable from information_schema.columns "
                        + "where table_schema=? and table_name=? and column_name=?", String.class, schema, table,
                        column);
                assertEquals("YES", nullable, column + " must be nullable (product-level lines carry no variant)");
            }
        }
    }

    // ------------------------------------------------------------- raw seeding

    private static CartItemResponse onlyItem(CartResponse cart) {
        assertEquals(1, cart.items().size(), "expected exactly one cart line");
        return cart.items().get(0);
    }

    private String addError(UUID productId, UUID variantId) {
        BusinessException e = assertThrows(BusinessException.class, () -> carts.add(userId, null,
                new AddCartItemRequest(storeId, productId, variantId, 1, null, List.of())),
                "expected rejection for variant reference " + variantId);
        return e.code();
    }

    private UUID seededLatteProduct(String base) {
        UUID productId = seedProductWithBase("SMOKE_" + suffix(), base);
        offerProduct(storeId, productId);
        return productId;
    }

    private UUID variant(UUID productId, String name, String price) {
        return variantRaw(productId, "V" + suffix(), name, price, "USD", true);
    }

    private UUID variantRaw(UUID productId, String code, String name, String price, String currency, boolean active) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into catalog.product_variants (variant_id, product_id, variant_code, variant_name, "
                + "price, currency_code, is_default, active, display_order, created_at, updated_at, version) "
                + "values (?,?,?,?,?,?,false,?,0,now(),now(),0)", id, productId, code, name, dec(price), currency,
                active);
        return id;
    }

    private UUID seedStore() {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into organization.stores (store_id, store_code, store_name, address, timezone, "
                + "default_currency, default_language, active, created_at, updated_at, version) "
                + "values (?, ?, '4B1 Branch', 'Addr', 'Asia/Phnom_Penh', 'USD', 'en-US', true, now(), now(), 0)",
                id, "B1" + UUID.randomUUID().toString().substring(0, 6).toUpperCase());
        return id;
    }

    private UUID seedCustomer() {
        UUID id = UUID.randomUUID();
        String email = "4b1-" + id + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)", id, "4B1 Customer", email, email, "en-US");
        jdbc.update("insert into customer.customer_profiles (customer_id, user_id, marketing_opt_in, created_at, "
                + "updated_at, version) values (?,?,false,now(),now(),0)", UUID.randomUUID(), id);
        return id;
    }

    private CurrentActor seedActor(String roleId) {
        UUID id = UUID.randomUUID();
        String email = "4b1-admin-" + id + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) "
                + "values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)", id, "4B1 Admin", email, email, "en-US");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, "
                + "assigned_at) values (?,?,?,?,?)", UUID.randomUUID(), id, UUID.fromString(roleId), null,
                java.sql.Timestamp.from(Instant.now()));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(id.toString(), null, List.of()));
        return actors.require();
    }

    private UUID seedProductWithBase(String code, String base) {
        UUID category = jdbc.queryForObject(
                "select category_id from catalog.product_categories order by category_id limit 1", UUID.class);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into catalog.products (product_id, product_code, category_id, product_name, base_price, "
                + "currency_code, preparation_minutes, tax_rate, customizable, active, created_at, updated_at, version) "
                + "values (?,?,?,?,?, 'USD', 5, 0.1, true, true, now(), now(), 0)",
                id, code, category, "IT " + code, dec(base));
        return id;
    }

    private void offerProduct(UUID store, UUID productId) {
        jdbc.update("insert into catalog.store_products (store_id, product_id, available, updated_at, version) "
                + "values (?,?,true,now(),0)", store, productId);
    }

    private UUID seedGroup(String code, String selectionType, int min) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into catalog.customization_groups (group_id, group_code, group_name, selection_type, "
                + "minimum_selections, maximum_selections, display_order, active, created_at, updated_at, version) "
                + "values (?,?,?,?,?,?,1,true,now(),now(),0)", id, code + "_" + suffix(), code, selectionType, min,
                null);
        return id;
    }

    private UUID seedPricedOption(UUID groupId, String name, String additionalPrice) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into catalog.customization_options (option_id, group_id, option_code, option_name, "
                + "additional_price, display_order, active, created_at, updated_at, version) "
                + "values (?,?,?,?,0,0,true,now(),now(),0)", id, groupId, name + "_" + suffix(), name);
        jdbc.update("update catalog.customization_options set additional_price=? where option_id=?",
                dec(additionalPrice), id);
        return id;
    }

    private void linkGroup(UUID productId, UUID groupId) {
        jdbc.update("insert into catalog.product_customization_groups (product_id, group_id, required, display_order) "
                + "values (?,?,false,0)", productId, groupId);
    }

    // ------------------------- reservation fixture: product + variant-specific recipe

    private record ReservedLatte(UUID productId, UUID variantId, UUID beans16, UUID cup16) {
    }

    private ReservedLatte reservedLatte() {
        UUID productId = seedProductWithBase("RESLATTE_" + suffix(), "3.50");
        offerProduct(storeId, productId);
        UUID beans12 = seedIngredient("BEANS12_" + suffix(), GRAM);
        UUID cup12 = seedIngredient("CUP12_" + suffix(), PIECE);
        UUID beans16 = seedIngredient("BEANS16_" + suffix(), GRAM);
        UUID cup16 = seedIngredient("CUP16_" + suffix(), PIECE);
        for (UUID ing : List.of(beans12, cup12, beans16, cup16)) {
            seedBalance(ing);
        }

        RecipeVersionResponse productDraft = recipeCommands.createDraft(admin, productId,
                new CreateRecipeDraftRequest(BigDecimal.ONE, null, "12oz",
                        List.of(recipeItem(beans12, "18", 0), recipeItem(cup12, "1", 1))));
        RecipeVersionResponse productActive = recipeCommands.activate(admin, productDraft.recipeVersionId(),
                productDraft.version());
        assignment.assignProduct(admin, productId, new AssignProductRecipeRequest(productActive.recipeVersionId(),
                jdbc.queryForObject("select version from catalog.products where product_id=?", Long.class, productId)));

        UUID variantId = variant(productId, "Large 16 oz", "14.00");
        RecipeVersionResponse variantDraft = recipeCommands.createDraft(admin, productId,
                new CreateRecipeDraftRequest(BigDecimal.ONE, null, "16oz",
                        List.of(recipeItem(beans16, "24", 0), recipeItem(cup16, "1", 1))));
        RecipeVersionResponse variantActive = recipeCommands.activate(admin, variantDraft.recipeVersionId(),
                variantDraft.version());
        assignment.assign(admin, variantId, new AssignVariantRecipeRequest(variantActive.recipeVersionId(),
                jdbc.queryForObject("select version from catalog.product_variants where variant_id=?", Long.class,
                        variantId)));
        return new ReservedLatte(productId, variantId, beans16, cup16);
    }

    private UUID seedIngredient(String code, UUID baseUnit) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into inventory.ingredients (ingredient_id, ingredient_code, ingredient_name, "
                + "base_unit_id, standard_cost, track_inventory, track_batches, active, created_at, updated_at, "
                + "version) values (?,?,?,?,0,true,false,true,now(),now(),0)", id, code, code, baseUnit);
        return id;
    }

    private void seedBalance(UUID ingredient) {
        jdbc.update("insert into inventory.inventory_balances (store_id, ingredient_id, current_stock, "
                + "reserved_stock, minimum_stock, average_unit_cost) values (?,?,1000,0,0,0.025)", storeId, ingredient);
    }

    private static RecipeItemRequest recipeItem(UUID ingredient, String qty, int order) {
        return new RecipeItemRequest(ingredient, dec(qty), BigDecimal.ZERO, null, RecipeScalingRule.FIXED, order,
                false, null);
    }

    // ------------------------------------------------------------ assertions

    private void assertReserved(UUID ingredient, String expected) {
        assertEquals(0, dec(expected).compareTo(jdbc.queryForObject("select reserved_stock from "
                + "inventory.inventory_balances where store_id=? and ingredient_id=?", BigDecimal.class, storeId,
                ingredient)), "reserved_stock for " + ingredient);
    }

    private void assertOnHand(UUID ingredient, String expected) {
        assertEquals(0, dec(expected).compareTo(jdbc.queryForObject("select current_stock from "
                + "inventory.inventory_balances where store_id=? and ingredient_id=?", BigDecimal.class, storeId,
                ingredient)), "current_stock must not move at cart add");
    }

    private void assertNoConsumption() {
        assertEquals(0, jdbc.queryForObject("select count(*) from inventory.stock_transactions "
                + "where transaction_type='SALE_CONSUMPTION' and store_id=?", Long.class, storeId));
    }

    private String reservationColumn(UUID cartItemId, String column) {
        return jdbc.queryForObject("select cast(" + column + " as text) from inventory.inventory_reservations "
                + "where cart_item_id=?", String.class, cartItemId);
    }

    private long cartItemCount() {
        return jdbc.queryForObject("select count(*) from cart.cart_items ci join cart.carts c "
                + "on c.cart_id=ci.cart_id where c.customer_id=(select customer_id from customer.customer_profiles "
                + "cp where cp.user_id=?)", Long.class, userId);
    }

    private static BigDecimal dec(String value) {
        return new BigDecimal(value);
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
