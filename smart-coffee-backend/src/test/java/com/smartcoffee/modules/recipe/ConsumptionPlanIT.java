package com.smartcoffee.modules.recipe;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.catalog.api.ProductVariantResponse;
import com.smartcoffee.modules.catalog.api.request.CreateProductVariantRequest;
import com.smartcoffee.modules.catalog.api.request.SetInventoryEffectRequest;
import com.smartcoffee.modules.catalog.application.InventoryEffectCalculator;
import com.smartcoffee.modules.catalog.application.OptionInventoryEffectCommandService;
import com.smartcoffee.modules.catalog.application.ProductVariantCommandService;
import com.smartcoffee.modules.cart.api.SelectedCustomizationRequest;
import com.smartcoffee.modules.catalog.domain.InventoryEffectType;
import com.smartcoffee.modules.recipe.api.RecipeVersionResponse;
import com.smartcoffee.modules.recipe.api.request.AssignProductRecipeRequest;
import com.smartcoffee.modules.recipe.api.request.AssignVariantRecipeRequest;
import com.smartcoffee.modules.recipe.api.request.CreateRecipeDraftRequest;
import com.smartcoffee.modules.recipe.api.request.RecipeItemRequest;
import com.smartcoffee.modules.recipe.application.CalculatedConsumptionLine;
import com.smartcoffee.modules.recipe.application.CalculatedConsumptionPlan;
import com.smartcoffee.modules.recipe.application.ConsumptionPlanService;
import com.smartcoffee.modules.recipe.application.RecipeAssignmentService;
import com.smartcoffee.modules.recipe.application.RecipeCommandService;
import com.smartcoffee.modules.recipe.application.RecipeDemandService;
import com.smartcoffee.modules.recipe.application.RecipeVersionResolver.Source;
import com.smartcoffee.modules.recipe.domain.RecipeScalingRule;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Slice 2B/2B.1 end-to-end verification against a fresh Testcontainers Postgres
 * (Flyway V001 → V044 + JPA + permissions + the real {@link ConsumptionPlanService}).
 * Mirrors the sibling {@code RecipeManagementIT} / {@code VariantAndEffectIT}: it
 * drives the same application services the controller uses (HTTP transport is
 * exercised by the OpenAPI contract IT; exception types here map 1:1 to the
 * required statuses).
 *
 * <p>Covers spec scenarios A–G plus no-write and checkout regression guarantees.
 * Selection is pointer-driven (V044): the product fallback pointer and the
 * variant override pointer may reference two DIFFERENT assignable (ACTIVE)
 * versions of the same product simultaneously (real Scenario D).
 */
class ConsumptionPlanIT extends IntegrationTestBase {

    private static final String SUPER_ADMIN = "00000000-0000-0000-0000-000000000002";
    private static final String CASHIER = "00000000-0000-0000-0000-000000000005";
    private static final UUID GRAM = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final UUID MILLILITER = UUID.fromString("60000000-0000-0000-0000-000000000003");
    private static final UUID PIECE = UUID.fromString("60000000-0000-0000-0000-000000000005");

    @Autowired JdbcTemplate jdbc;
    @Autowired CurrentActorProvider actors;
    @Autowired ConsumptionPlanService plans;
    @Autowired RecipeCommandService recipes;
    @Autowired RecipeAssignmentService assignment;
    @Autowired ProductVariantCommandService variantCommands;
    @Autowired OptionInventoryEffectCommandService effectCommands;
    @Autowired RecipeDemandService demand;
    @Autowired InventoryEffectCalculator calculator;

    private CurrentActor admin;

    @BeforeEach
    void authenticateAdmin() {
        admin = seedActor(SUPER_ADMIN);
    }

    // ---------------------------------------------------- Scenario A: base only

    @Test
    void regularLatteMatchesBaseRecipe() {
        Latte l = newLatteProductWithActiveRecipe();
        CalculatedConsumptionPlan plan = plans.preview(admin, l.productId, null, BigDecimal.ONE, List.of());

        assertEquals(Source.PRODUCT, plan.resolutionSource());
        assertEquals(0, new BigDecimal("18").compareTo(qty(plan, l.beans)));
        assertEquals(0, new BigDecimal("180").compareTo(qty(plan, l.milk)));
        assertEquals(0, new BigDecimal("20").compareTo(qty(plan, l.syrup)));
        assertEquals(0, new BigDecimal("120").compareTo(qty(plan, l.ice)));
        assertEquals(0, new BigDecimal("1").compareTo(qty(plan, l.cup)));
    }

    // ---------------------------------------- Scenario B: customization effects

    @Test
    void customizedLatteAppliesReplaceMultiplyAndAdd() {
        Latte l = newLatteProductWithActiveRecipe();
        // Oat replaces milk; sugar 50%; one extra espresso shot (+9 g beans).
        EffRef oatOption = effectOption(l.productId, "MILK", "SINGLE", 0,
                "oat", InventoryEffectType.REPLACE, l.milk, l.oat, null, null);
        EffRef sugar50 = effectOption(l.productId, "SUGAR", "SINGLE", 0,
                "sugar-50", InventoryEffectType.MULTIPLY, l.syrup, null, null, new BigDecimal("0.50"));
        EffRef extra = effectOption(l.productId, "EXTRAS", "MULTIPLE", 0,
                "extra-shot", InventoryEffectType.ADD, null, l.beans, new BigDecimal("9"), null);

        CalculatedConsumptionPlan plan = plans.preview(admin, l.productId, null, BigDecimal.ONE, List.of(
                sel(oatOption), sel(sugar50), sel(extra)));

        assertEquals(0, new BigDecimal("27").compareTo(qty(plan, l.beans)), "18 + 9 extra shot");
        assertEquals(0, BigDecimal.ZERO.compareTo(nvl(plan, l.milk)), "fresh milk fully replaced");
        assertEquals(0, new BigDecimal("180").compareTo(qty(plan, l.oat)), "oat inherits the 180 ml");
        assertEquals(0, new BigDecimal("10").compareTo(qty(plan, l.syrup)), "20 * 0.50");
        assertEquals(0, new BigDecimal("120").compareTo(qty(plan, l.ice)), "ice untouched");
        assertEquals(0, new BigDecimal("1").compareTo(qty(plan, l.cup)), "cup untouched");
    }

    // --------------------------------------------------- Scenario C: quantity 3

    @Test
    void quantityThreeScalesOnce() {
        Latte l = newLatteProductWithActiveRecipe();
        EffRef oatOption = effectOption(l.productId, "MILK", "SINGLE", 0,
                "oat", InventoryEffectType.REPLACE, l.milk, l.oat, null, null);
        EffRef sugar50 = effectOption(l.productId, "SUGAR", "SINGLE", 0,
                "sugar-50", InventoryEffectType.MULTIPLY, l.syrup, null, null, new BigDecimal("0.50"));
        EffRef extra = effectOption(l.productId, "EXTRAS", "MULTIPLE", 0,
                "extra-shot", InventoryEffectType.ADD, null, l.beans, new BigDecimal("9"), null);

        CalculatedConsumptionPlan plan = plans.preview(admin, l.productId, null, new BigDecimal("3"), List.of(
                sel(oatOption), sel(sugar50), sel(extra)));

        assertEquals(0, new BigDecimal("81").compareTo(qty(plan, l.beans)), "(18+9)*3");
        assertEquals(0, new BigDecimal("540").compareTo(qty(plan, l.oat)), "180*3");
        assertEquals(0, new BigDecimal("30").compareTo(qty(plan, l.syrup)), "(20*0.5)*3");
        assertEquals(0, new BigDecimal("360").compareTo(qty(plan, l.ice)), "120*3");
        assertEquals(0, new BigDecimal("3").compareTo(qty(plan, l.cup)), "1*3");
    }

    // -------------------------------------- Scenario D: real variant override

    @Test
    void variantOverrideResolvesADifferentActiveRecipe() {
        // Slice 2B.1 REAL override (V044 dropped one-ACTIVE-per-product): the 12oz
        // product fallback and the 16oz variant override are two DISTINCT versions,
        // both ACTIVE (published + assignable), pinned to different pointers at the
        // same time. Removing the override must restore the 12oz fallback — the
        // 16oz version never leaks into the product path and vice versa.
        Latte l = newLatteProductWithActiveRecipe(); // 12oz: 18 g beans / 180 ml milk / standard cup
        UUID largeCup = seedIngredient("CUP16_" + suffix(), PIECE);
        List<RecipeItemRequest> largeItems = List.of(
                item(l.beans, "24", 0), item(l.milk, "240", 1), item(l.syrup, "30", 2),
                item(l.ice, "160", 3), item(largeCup, "1", 4));
        RecipeVersionResponse largeDraft = recipes.createDraft(admin, l.productId,
                new CreateRecipeDraftRequest(BigDecimal.ONE, null, "latte large", largeItems));
        RecipeVersionResponse largeActive = recipes.activate(admin, largeDraft.recipeVersionId(), largeDraft.version());
        assertNotEquals(l.activeRecipeId, largeActive.recipeVersionId());

        // The contradiction proof: BOTH versions are ACTIVE for the SAME product now.
        assertEquals(2, jdbc.queryForObject(
                "select count(*) from recipe.recipe_versions where product_id=? and status='ACTIVE'",
                Integer.class, l.productId), "two distinct assignable versions coexist");

        UUID variantId = createVariant(l.productId);
        assignment.assign(admin, variantId,
                new AssignVariantRecipeRequest(largeActive.recipeVersionId(), variantVersion(variantId)));

        // Product path keeps the 12oz fallback untouched by the variant assignment.
        CalculatedConsumptionPlan viaProduct = plans.preview(admin, l.productId, null, BigDecimal.ONE, List.of());
        assertEquals(Source.PRODUCT, viaProduct.resolutionSource());
        assertEquals(l.activeRecipeId, viaProduct.recipeVersionId());
        assertEquals(0, new BigDecimal("18").compareTo(qty(viaProduct, l.beans)));
        assertEquals(0, new BigDecimal("180").compareTo(qty(viaProduct, l.milk)), "12oz keeps 180 ml milk");
        assertEquals(0, new BigDecimal("1").compareTo(qty(viaProduct, l.cup)), "12oz uses the standard cup");

        // Variant path resolves the DISTINCT 16oz version with genuinely different lines.
        CalculatedConsumptionPlan viaVariant = plans.preview(admin, l.productId, variantId, BigDecimal.ONE, List.of());
        assertEquals(Source.VARIANT, viaVariant.resolutionSource());
        assertEquals(largeActive.recipeVersionId(), viaVariant.recipeVersionId());
        assertEquals(0, new BigDecimal("24").compareTo(qty(viaVariant, l.beans)), "16oz overrides beans 18 -> 24");
        assertEquals(0, new BigDecimal("240").compareTo(qty(viaVariant, l.milk)), "16oz overrides milk 180 -> 240");
        assertEquals(0, new BigDecimal("1").compareTo(qty(viaVariant, largeCup)), "16oz uses its own cup");
        assertEquals(0, BigDecimal.ZERO.compareTo(nvl(viaVariant, l.cup)), "standard cup is NOT in the 16oz recipe");

        // Removing the override restores the product fallback (the pointer, not status).
        assignment.assign(admin, variantId, new AssignVariantRecipeRequest(null, variantVersion(variantId)));
        CalculatedConsumptionPlan restored = plans.preview(admin, l.productId, variantId, BigDecimal.ONE, List.of());
        assertEquals(Source.PRODUCT, restored.resolutionSource());
        assertEquals(l.activeRecipeId, restored.recipeVersionId());
        assertEquals(0, new BigDecimal("18").compareTo(qty(restored, l.beans)), "fallback recipe, not the 16oz one");
        assertEquals(0, new BigDecimal("180").compareTo(qty(restored, l.milk)));
        // Both versions remain ACTIVE and readable afterwards — nothing was superseded.
        for (UUID id : List.of(l.activeRecipeId, largeActive.recipeVersionId())) {
            assertEquals("ACTIVE", jdbc.queryForObject(
                    "select status from recipe.recipe_versions where recipe_version_id = ?", String.class, id),
                    "publishing/assigning one version must never supersede another");
        }
    }

    // ---------------------------------------- Scenario E: missing recipe by mode

    @Test
    void recipelessMakeToOrderIsEmpty() {
        UUID productId = seedProduct("MTO_" + suffix());
        CalculatedConsumptionPlan plan = plans.preview(admin, productId, null, BigDecimal.ONE, List.of());
        assertEquals(Source.NONE, plan.resolutionSource());
        assertTrue(plan.lines().isEmpty());
    }

    @Test
    void recipeBasedWithoutActiveRecipeIsRejected() {
        UUID productId = seedProduct("RB_" + suffix());
        jdbc.update("update catalog.products set stock_mode='RECIPE_BASED' where product_id = ?", productId);
        ValidationException ex = assertThrows(ValidationException.class, () ->
                plans.preview(admin, productId, null, BigDecimal.ONE, List.of()));
        assertEquals("ACTIVE_RECIPE_REQUIRED", ex.code());
    }

    // ------------------------------------------ Scenario F: invalid selections

    @Test
    void requiredGroupMissingFailsValidation() {
        Latte l = newLatteProductWithActiveRecipe();
        // A required SINGLE group with one active option; selecting nothing must fail.
        UUID groupId = seedGroup("SIZE", "SINGLE", 1, null);
        seedOption(groupId, "reg", "Regular");
        linkGroup(l.productId, groupId);
        ValidationException ex = assertThrows(ValidationException.class, () ->
                plans.preview(admin, l.productId, null, BigDecimal.ONE, List.of()));
        assertTrue(ex.getMessage().toLowerCase().contains("required"), ex.getMessage());
    }

    @Test
    void optionFromAnotherProductIsRejected() {
        Latte mine = newLatteProductWithActiveRecipe();
        Latte other = newLatteProductWithActiveRecipe();
        EffRef foreignOption = effectOption(other.productId, "MILK", "SINGLE", 0,
                "oat", InventoryEffectType.REPLACE, other.milk, other.oat, null, null);
        // Selecting the other product's option on this product: group not in this product.
        assertThrows(ValidationException.class, () ->
                plans.preview(admin, mine.productId, null, BigDecimal.ONE, List.of(sel(foreignOption))));
    }

    // ---------------------------------------------- Scenario G: authorization

    @Test
    void cashierCannotPreview() {
        Latte l = newLatteProductWithActiveRecipe();
        CurrentActor cashier = seedActor(CASHIER);
        assertThrows(ForbiddenException.class, () ->
                plans.preview(cashier, l.productId, null, BigDecimal.ONE, List.of()));
    }

    @Test
    void unknownProductIsNotFound() {
        assertThrows(ResourceNotFoundException.class, () ->
                plans.preview(admin, UUID.randomUUID(), null, BigDecimal.ONE, List.of()));
    }

    // ----------------------------------------------- no-write + checkout regressed

    @Test
    void previewWritesNothingAndCheckoutDemandIsUnchanged() {
        Latte l = newLatteProductWithActiveRecipe();
        EffRef oatOption = effectOption(l.productId, "MILK", "SINGLE", 0,
                "oat", InventoryEffectType.REPLACE, l.milk, l.oat, null, null);

        Map<String, Long> before = writeCounts();
        // Base-map identity guard: the calculator input must not be mutated either.
        Map<UUID, BigDecimal> calcBase = new java.util.LinkedHashMap<>(Map.of(l.beans, new BigDecimal("18")));

        CalculatedConsumptionPlan plan = plans.preview(admin, l.productId, null, new BigDecimal("2"), List.of(sel(oatOption)));
        assertFalse(plan.lines().isEmpty());

        Map<UUID, BigDecimal> calcOut = calculator.calculate(calcBase, List.of(oatOption.optionId()));
        assertEquals(0, new BigDecimal("18").compareTo(calcBase.get(l.beans)),
                "InventoryEffectCalculator must not mutate its input map");
        assertNotNull(calcOut);

        Map<String, Long> after = writeCounts();
        assertEquals(before, after, "preview must create no stock / cart / order / recipe rows");

        // Checkout path unchanged: product-only demand still equals the base recipe.
        Map<UUID, BigDecimal> checkoutDemand =
                demand.calculate(List.of(new RecipeDemandService.ProductDemand(l.productId, 1)));
        assertEquals(0, new BigDecimal("18").compareTo(checkoutDemand.get(l.beans)));
        assertEquals(0, new BigDecimal("180").compareTo(checkoutDemand.get(l.milk)));
        assertNull(checkoutDemand.get(l.oat), "checkout ignores customization effects");
    }

    // -------------------------------------------------------------- scaffolding

    private record Latte(UUID productId, UUID beans, UUID milk, UUID syrup, UUID oat, UUID ice, UUID cup,
            UUID lid, UUID activeRecipeId) {
    }

    private Latte newLatteProductWithActiveRecipe() {
        UUID productId = seedProduct("LATTE_" + suffix());
        UUID beans = seedIngredient("BEANS_" + suffix(), GRAM);
        UUID milk = seedIngredient("MILK_" + suffix(), MILLILITER);
        UUID syrup = seedIngredient("SYRUP_" + suffix(), MILLILITER);
        UUID oat = seedIngredient("OAT_" + suffix(), MILLILITER);
        UUID ice = seedIngredient("ICE_" + suffix(), GRAM);
        UUID cup = seedIngredient("CUP_" + suffix(), PIECE);
        UUID lid = seedIngredient("LID_" + suffix(), PIECE);

        List<RecipeItemRequest> items = List.of(
                item(beans, "18", 0), item(milk, "180", 1), item(syrup, "20", 2),
                item(ice, "120", 3), item(cup, "1", 4), item(lid, "1", 5));
        RecipeVersionResponse draft = recipes.createDraft(admin, productId,
                new CreateRecipeDraftRequest(BigDecimal.ONE, null, "latte", items));
        RecipeVersionResponse active = recipes.activate(admin, draft.recipeVersionId(), draft.version());
        // Slice 2B.1: publication alone selects nothing — the product fallback
        // pointer is what makes the recipe resolve for the product path.
        assignment.assignProduct(admin, productId,
                new AssignProductRecipeRequest(active.recipeVersionId(), productVersion(productId)));
        return new Latte(productId, beans, milk, syrup, oat, ice, cup, lid, active.recipeVersionId());
    }

    private static RecipeItemRequest item(UUID ingredient, String qty, int order) {
        return new RecipeItemRequest(ingredient, new BigDecimal(qty), BigDecimal.ZERO,
                null, RecipeScalingRule.FIXED, order, false, null);
    }

    /** Reference to a seeded group+option pair so selections can carry the real group id. */
    private record EffRef(UUID groupId, UUID optionId) {
    }

    /** Creates an ACTIVE customization option (allowed for the product) with an effect. */
    private EffRef effectOption(UUID productId, String groupCode, String selectionType, int min,
            String optionCode, InventoryEffectType type, UUID source, UUID target,
            BigDecimal qty, BigDecimal mult) {
        UUID groupId = seedGroup(groupCode, selectionType, min, null);
        UUID optionId = seedOption(groupId, optionCode + "_" + suffix(), optionCode);
        linkGroup(productId, groupId);
        effectCommands.set(admin, optionId, null,
                new SetInventoryEffectRequest(type, source, target, qty, mult, null));
        return new EffRef(groupId, optionId);
    }

    private static SelectedCustomizationRequest sel(EffRef ref) {
        return new SelectedCustomizationRequest(ref.groupId(), ref.optionId(), 1);
    }

    private long variantVersion(UUID variantId) {
        return jdbc.queryForObject("select version from catalog.product_variants where variant_id = ?",
                Long.class, variantId);
    }

    private long productVersion(UUID productId) {
        return jdbc.queryForObject("select version from catalog.products where product_id = ?",
                Long.class, productId);
    }

    private UUID createVariant(UUID productId) {
        ProductVariantResponse v = variantCommands.create(admin, productId,
                new CreateProductVariantRequest("VAR_" + suffix(), "Large", null, null,
                        new BigDecimal("14"), null, "USD", false, true, 0, null));
        return v.variantId();
    }

    private Map<String, Long> writeCounts() {
        Map<String, Long> m = new java.util.LinkedHashMap<>();
        for (String t : List.of("inventory.inventory_balances", "inventory.stock_transactions",
                "inventory.stock_transaction_lines", "inventory.inventory_reservations",
                "cart.carts", "cart.cart_items", "recipe.recipe_versions", "recipe.recipe_version_lines")) {
            m.put(t, jdbc.queryForObject("select count(*) from " + t, Long.class));
        }
        return m;
    }

    @SuppressWarnings("null") // Optional is never empty here: the fixture always seeds the line
    private static BigDecimal qty(CalculatedConsumptionPlan plan, UUID ingredientId) {
        return plan.lines().stream().filter(l -> l.ingredientId().equals(ingredientId))
                .map(CalculatedConsumptionLine::quantity).findFirst().orElseThrow(
                        () -> new AssertionError("no line for ingredient " + ingredientId));
    }

    @SuppressWarnings("null")
    private static BigDecimal nvl(CalculatedConsumptionPlan plan, UUID ingredientId) {
        return plan.lines().stream().filter(l -> l.ingredientId().equals(ingredientId))
                .map(CalculatedConsumptionLine::quantity).findFirst().orElse(BigDecimal.ZERO);
    }

    // ------------------------------------------------------------- raw seeding

    private UUID seedProduct(String code) {
        UUID cat = jdbc.queryForObject(
                "select category_id from catalog.product_categories order by category_id limit 1", UUID.class);
        UUID id = UUID.randomUUID();
        jdbc.update("insert into catalog.products (product_id, product_code, category_id, product_name, base_price, "
                + "currency_code, preparation_minutes, tax_rate, customizable, active, created_at, updated_at, version) "
                + "values (?,?,?,?,?, 'USD', 5, 0.1, false, true, now(), now(), 0)",
                id, code, cat, "IT " + code, new BigDecimal("10"));
        return id;
    }

    private UUID seedIngredient(String code, UUID baseUnit) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into inventory.ingredients (ingredient_id, ingredient_code, ingredient_name, base_unit_id, "
                + "standard_cost, track_inventory, track_batches, active, created_at, updated_at, version) "
                + "values (?,?,?,?, 0, false, false, true, now(), now(), 0)", id, code, code, baseUnit);
        return id;
    }

    private UUID seedGroup(String code, String selectionType, int min, Integer max) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into catalog.customization_groups (group_id, group_code, group_name, selection_type, "
                + "minimum_selections, maximum_selections, display_order, active, created_at, updated_at, version) "
                + "values (?,?,?,?,?,?," + Math.abs(code.hashCode() % 100) + ", true, now(), now(), 0)",
                id, code + "_" + suffix(), code, selectionType, min, max);
        return id;
    }

    private UUID seedOption(UUID groupId, String code, String name) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into catalog.customization_options (option_id, group_id, option_code, option_name, "
                + "additional_price, display_order, active, created_at, updated_at, version) "
                + "values (?,?,?, ?, 0, 0, true, now(), now(), 0)", id, groupId, code, name);
        return id;
    }

    private void linkGroup(UUID productId, UUID groupId) {
        jdbc.update("insert into catalog.product_customization_groups (product_id, group_id, required, display_order) "
                + "values (?,?, false, 0)", productId, groupId);
    }

    private CurrentActor seedActor(String roleId) {
        UUID userId = UUID.randomUUID();
        String email = "consumption-it-" + userId + "@smart.coffee";
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)",
                userId, "IT User", email, email, "en-US");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, assigned_at) "
                + "values (?,?,?,?,?)", UUID.randomUUID(), userId, UUID.fromString(roleId), null,
                java.sql.Timestamp.from(Instant.now()));
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
        return actors.require();
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8);
    }
}
