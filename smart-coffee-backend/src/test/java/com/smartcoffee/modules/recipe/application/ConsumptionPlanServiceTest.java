package com.smartcoffee.modules.recipe.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.modules.catalog.domain.CustomizationOption;
import com.smartcoffee.modules.catalog.domain.InventoryEffectType;
import com.smartcoffee.modules.catalog.domain.Product;
import com.smartcoffee.modules.catalog.domain.StockMode;
import com.smartcoffee.modules.catalog.persistence.CustomizationGroupRepository;
import com.smartcoffee.modules.catalog.persistence.CustomizationOptionRepository;
import com.smartcoffee.modules.catalog.persistence.ProductRepository;
import com.smartcoffee.modules.catalog.persistence.ProductVariantRepository;
import com.smartcoffee.modules.cart.api.SelectedCustomizationRequest;
import com.smartcoffee.modules.cart.application.CartCatalogGateway;
import com.smartcoffee.modules.cart.application.CartCatalogGateway.GroupRule;
import com.smartcoffee.modules.cart.application.CartCatalogGateway.OptionRule;
import com.smartcoffee.modules.cart.application.CartCustomizationValidator;
import com.smartcoffee.modules.inventory.domain.Ingredient;
import com.smartcoffee.modules.inventory.domain.UnitOfMeasure;
import com.smartcoffee.modules.inventory.domain.UnitType;
import com.smartcoffee.modules.inventory.persistence.IngredientRepository;
import com.smartcoffee.modules.inventory.persistence.UnitOfMeasureRepository;
import com.smartcoffee.modules.recipe.application.RecipeVersionResolver.Resolved;
import com.smartcoffee.modules.recipe.application.RecipeVersionResolver.Source;
import com.smartcoffee.modules.recipe.domain.RecipeScalingRule;
import com.smartcoffee.modules.recipe.domain.RecipeVersion;
import com.smartcoffee.modules.recipe.domain.RecipeVersionLine;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Focused unit tests for the Slice 2B {@link ConsumptionPlanService} pipeline:
 * effect arithmetic (ADD / REMOVE / REPLACE / MULTIPLY), canonical ordering that is
 * independent of request order, option-quantity and product-quantity scaling applied
 * exactly once, conflict detection, no-recipe behavior, authorization and — critically
 * — immutability of the source recipe. Persistence and resolution are mocked; the
 * real {@link CartCustomizationValidator}, {@link RecipeLineNormalizer} and
 * {@link PermissionEvaluator} run for real.
 */
class ConsumptionPlanServiceTest {

    private static final UUID GRAM = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final UUID MILLILITER = UUID.fromString("60000000-0000-0000-0000-000000000003");
    private static final UUID PIECE = UUID.fromString("60000000-0000-0000-0000-000000000005");
    private static final UUID GROUP = UUID.randomUUID();

    private final UUID productId = UUID.randomUUID();
    private final UUID beanId = UUID.randomUUID();
    private final UUID milkId = UUID.randomUUID();
    private final UUID syrupId = UUID.randomUUID();
    private final UUID oatId = UUID.randomUUID();
    private final UUID iceId = UUID.randomUUID();
    private final UUID cupId = UUID.randomUUID();

    private ProductRepository products;
    private RecipeVersionResolver resolver;
    private CartCatalogGateway gateway;
    private CustomizationOptionRepository options;
    private CustomizationGroupRepository groups;
    private ConsumptionPlanService service;
    private RecipeVersion latteRecipe;
    private final List<CustomizationOption> catalogOptions = new ArrayList<>();

    private final CurrentActor viewer =
            new CurrentActor(UUID.randomUUID(), "USER", Set.of("recipe:view"), Set.of(), true);
    private final CurrentActor noPerms =
            new CurrentActor(UUID.randomUUID(), "USER", Set.of(), Set.of(), false);

    @BeforeEach
    void wire() {
        IngredientRepository ingredients = mock(IngredientRepository.class);
        UnitOfMeasureRepository units = mock(UnitOfMeasureRepository.class);
        products = mock(ProductRepository.class);
        resolver = mock(RecipeVersionResolver.class);
        gateway = mock(CartCatalogGateway.class);
        options = mock(CustomizationOptionRepository.class);
        groups = mock(CustomizationGroupRepository.class);
        ProductVariantRepository variants = mock(ProductVariantRepository.class);

        when(units.findAllById(anyIterable())).thenReturn(List.of(
                unit(GRAM, "GRAM", UnitType.MASS, BigDecimal.ONE),
                unit(MILLILITER, "MILLILITER", UnitType.VOLUME, BigDecimal.ONE),
                unit(PIECE, "PIECE", UnitType.COUNT, BigDecimal.ONE)));
        when(ingredients.findAllById(anyIterable())).thenReturn(List.of(
                ingredient(beanId, "BEANS", "Coffee Beans", GRAM),
                ingredient(milkId, "MILK", "Fresh Milk", MILLILITER),
                ingredient(syrupId, "SYRUP", "Sugar Syrup", MILLILITER),
                ingredient(oatId, "OAT", "Oat Milk", MILLILITER),
                ingredient(iceId, "ICE", "Ice", GRAM),
                ingredient(cupId, "CUP", "Cup 12oz", PIECE)));

        latteRecipe = latteRecipe();
        service = new ConsumptionPlanService(resolver, products, variants, gateway,
                new CartCustomizationValidator(), groups, options,
                new RecipeLineNormalizer(ingredients, units), ingredients, units, new PermissionEvaluator());

        Product product = activeProduct(StockMode.MADE_TO_ORDER);
        when(products.findById(productId)).thenReturn(java.util.Optional.of(product));
        when(resolver.resolve(any(), any())).thenReturn(new Resolved(Source.PRODUCT, latteRecipe));
    }

    // ----------------------------------------------------------- resolution

    @Test
    void missingPermissionIsForbidden() {
        stubCatalog();
        assertThrows(ForbiddenException.class, () ->
                service.preview(noPerms, productId, null, BigDecimal.ONE, List.of()));
    }

    @Test
    void unknownProductIsNotFound() {
        when(products.findById(productId)).thenReturn(java.util.Optional.empty());
        assertThrows(ResourceNotFoundException.class, () ->
                service.preview(viewer, productId, null, BigDecimal.ONE, List.of()));
    }

    @Test
    void recipelessMakeToOrderYieldsEmptyPlanNotError() {
        stubProduct(StockMode.MADE_TO_ORDER);
        when(resolver.resolve(any(), any())).thenReturn(new Resolved(Source.NONE, null));
        stubCatalog();
        CalculatedConsumptionPlan plan = service.preview(viewer, productId, null, BigDecimal.ONE, List.of());
        assertEquals(Source.NONE, plan.resolutionSource());
        assertTrue(plan.lines().isEmpty());
    }

    @Test
    void recipeBasedWithoutActiveRecipeIsRejected() {
        stubProduct(StockMode.RECIPE_BASED);
        when(resolver.resolve(any(), any())).thenReturn(new Resolved(Source.NONE, null));
        stubCatalog();
        ValidationException ex = assertThrows(ValidationException.class, () ->
                service.preview(viewer, productId, null, BigDecimal.ONE, List.of()));
        assertEquals("ACTIVE_RECIPE_REQUIRED", ex.code());
    }

    @Test
    void finishedGoodAndBatchProductionReturnEmptyDeferredPlans() {
        stubCatalog();
        for (StockMode mode : List.of(StockMode.FINISHED_GOOD, StockMode.BATCH_PRODUCTION, StockMode.NOT_TRACKED)) {
            stubProduct(mode);
            CalculatedConsumptionPlan plan = service.preview(viewer, productId, null, BigDecimal.ONE, List.of());
            assertEquals(Source.NONE, plan.resolutionSource(), mode + " must not fabricate raw demand");
            assertTrue(plan.lines().isEmpty(), mode + " plan must be empty");
            assertFalse(plan.warnings().isEmpty(), mode + " should carry a deferral warning");
        }
    }

    // ------------------------------------------------------------- base math

    @Test
    void productQuantityAppliedExactlyOnce() {
        stubCatalog();
        CalculatedConsumptionPlan plan = service.preview(viewer, productId, null, new BigDecimal("3"), List.of());
        assertEquals(0, new BigDecimal("54").compareTo(qty(plan, beanId)), "18 g * 3");
        assertEquals(0, new BigDecimal("540").compareTo(qty(plan, milkId)), "180 ml * 3");
        assertEquals(0, new BigDecimal("3").compareTo(qty(plan, cupId)), "1 piece * 3");
    }

    // ----------------------------------------------------------------- ADD

    @Test
    void addScalesByOptionQuantityAndProductQuantity() {
        addOption("extra-shot", InventoryEffectType.ADD, null, beanId, new BigDecimal("9"), null);
        stubCatalog();
        // 2 shots, 3 products: 18 + (9 * 2) = 36 per unit; *3 = 108.
        CalculatedConsumptionPlan plan = service.preview(viewer, productId, null, new BigDecimal("3"),
                List.of(selection(catalogOptions.get(0).id(), 2)));
        assertEquals(0, new BigDecimal("108").compareTo(qty(plan, beanId)));
    }

    // -------------------------------------------------------------- REMOVE

    @Test
    void removeFullClearsSourceOnly() {
        addOption("no-ice", InventoryEffectType.REMOVE, iceId, null, null, null);
        stubCatalog();
        CalculatedConsumptionPlan plan = service.preview(viewer, productId, null, BigDecimal.ONE,
                List.of(selection(catalogOptions.get(0).id(), 1)));
        assertEquals(0, BigDecimal.ZERO.compareTo(nvl(plan, iceId)), "ice removed entirely");
        assertEquals(0, new BigDecimal("180").compareTo(qty(plan, milkId)), "milk untouched");
    }

    @Test
    void removePartialFloorsAtZero() {
        addOption("less-ice", InventoryEffectType.REMOVE, iceId, null, new BigDecimal("50"), null);
        stubCatalog();
        CalculatedConsumptionPlan plan = service.preview(viewer, productId, null, BigDecimal.ONE,
                List.of(selection(catalogOptions.get(0).id(), 1)));
        assertEquals(0, new BigDecimal("70").compareTo(qty(plan, iceId)), "120 - 50");
    }

    // ------------------------------------------------------------- REPLACE

    @Test
    void replaceTransfersFullSourceAmountToTarget() {
        addOption("oat", InventoryEffectType.REPLACE, milkId, oatId, null, null);
        stubCatalog();
        CalculatedConsumptionPlan plan = service.preview(viewer, productId, null, BigDecimal.ONE,
                List.of(selection(catalogOptions.get(0).id(), 1)));
        assertEquals(0, BigDecimal.ZERO.compareTo(nvl(plan, milkId)), "fresh milk cleared");
        assertEquals(0, new BigDecimal("180").compareTo(qty(plan, oatId)), "oat milk inherits 180 ml");
    }

    // ----------------------------------------------------------- MULTIPLY

    @Test
    void multiplyAffectsOnlyItsIngredient() {
        addOption("sugar-50", InventoryEffectType.MULTIPLY, syrupId, null, null, new BigDecimal("0.50"));
        stubCatalog();
        CalculatedConsumptionPlan plan = service.preview(viewer, productId, null, new BigDecimal("3"),
                List.of(selection(catalogOptions.get(0).id(), 1)));
        // 20 * 0.50 * 3 = 30
        assertEquals(0, new BigDecimal("30").compareTo(qty(plan, syrupId)));
        assertEquals(0, new BigDecimal("54").compareTo(qty(plan, beanId)), "coffee unaffected");
    }

    // ------------------------------------------------------- interactions

    @Test
    void requestOrderDoesNotAffectResult() {
        UUID extra = addOption("extra", InventoryEffectType.ADD, null, syrupId, new BigDecimal("10"), null).id();
        UUID sugar = addOption("sugar-50", InventoryEffectType.MULTIPLY, syrupId, null, null,
                new BigDecimal("0.50")).id();
        stubCatalog();
        // Canonical order MULTIPLY(phase 2) before ADD(phase 3): (20 * 0.50) + 10 = 20.
        CalculatedConsumptionPlan one = service.preview(viewer, productId, null, BigDecimal.ONE,
                List.of(selection(extra, 1), selection(sugar, 1)));
        CalculatedConsumptionPlan two = service.preview(viewer, productId, null, BigDecimal.ONE,
                List.of(selection(sugar, 1), selection(extra, 1)));
        assertEquals(0, new BigDecimal("20").compareTo(qty(one, syrupId)));
        assertEquals(qty(one, syrupId), qty(two, syrupId), "output must be order-independent");
    }

    @Test
    void conflictingReplacesAreRejected() {
        UUID a = addOption("rep-a", InventoryEffectType.REPLACE, milkId, oatId, null, null).id();
        UUID b = addOption("rep-b", InventoryEffectType.REPLACE, milkId, syrupId, new BigDecimal("10"), null).id();
        stubCatalog();
        ValidationException ex = assertThrows(ValidationException.class, () ->
                service.preview(viewer, productId, null, BigDecimal.ONE,
                        List.of(selection(a, 1), selection(b, 1))));
        assertEquals("CONFLICTING_INVENTORY_EFFECTS", ex.code());
    }

    // ------------------------------------------------------- immutability

    @Test
    void sourceRecipeIsNeverMutated() {
        addOption("oat", InventoryEffectType.REPLACE, milkId, oatId, null, null);
        addOption("extra", InventoryEffectType.ADD, null, beanId, new BigDecimal("9"), null);
        stubCatalog();
        BigDecimal milkBefore = latteRecipe.lines().stream()
                .filter(l -> l.ingredientId().equals(milkId)).findFirst().orElseThrow().quantityRequiredBase();
        int lineCountBefore = latteRecipe.lines().size();
        service.preview(viewer, productId, null, new BigDecimal("3"),
                List.of(selection(catalogOptions.get(0).id(), 1), selection(catalogOptions.get(1).id(), 2)));
        assertEquals(lineCountBefore, latteRecipe.lines().size(), "no lines added/removed");
        BigDecimal milkAfter = latteRecipe.lines().stream()
                .filter(l -> l.ingredientId().equals(milkId)).findFirst().orElseThrow().quantityRequiredBase();
        assertEquals(0, milkBefore.compareTo(milkAfter), "line quantity untouched");
    }

    // ------------------------------------------------------------- fixtures

    private void stubProduct(StockMode mode) {
        Product p = activeProduct(mode);
        when(products.findById(productId)).thenReturn(java.util.Optional.of(p));
    }

    private void stubCatalog() {
        List<OptionRule> ruleOptions = new ArrayList<>();
        for (CustomizationOption o : catalogOptions) {
            ruleOptions.add(new OptionRule(o.id(), o.name(), BigDecimal.ZERO, true));
        }
        GroupRule rule = new GroupRule(GROUP, "Extras", "MULTIPLE", 0, null, ruleOptions);
        when(gateway.groupRules(productId)).thenReturn(List.of(rule));
        when(options.findAllById(anyIterable())).thenReturn(new ArrayList<>(catalogOptions));
        when(groups.findAllById(anyIterable())).thenReturn(List.of());
    }

    private CustomizationOption addOption(String code, InventoryEffectType type, UUID source, UUID target,
            BigDecimal qty, BigDecimal mult) {
        CustomizationOption o = option(UUID.randomUUID(), code, type, source, target, qty, mult);
        catalogOptions.add(o);
        return o;
    }

    private static SelectedCustomizationRequest selection(UUID optionId, int quantity) {
        return new SelectedCustomizationRequest(GROUP, optionId, quantity);
    }

    @SuppressWarnings("null") // Optional is never empty here: the fixture always seeds the line
    private static BigDecimal qty(CalculatedConsumptionPlan plan, UUID ingredientId) {
        return plan.lines().stream().filter(l -> l.ingredientId().equals(ingredientId))
                .map(CalculatedConsumptionLine::quantity).findFirst().orElseThrow();
    }

    @SuppressWarnings("null")
    private static BigDecimal nvl(CalculatedConsumptionPlan plan, UUID ingredientId) {
        return plan.lines().stream().filter(l -> l.ingredientId().equals(ingredientId))
                .map(CalculatedConsumptionLine::quantity).findFirst().orElse(BigDecimal.ZERO);
    }

    private RecipeVersion latteRecipe() {
        RecipeVersion v = RecipeVersion.createDraft(productId, 1, BigDecimal.ONE, null, "latte", null);
        v.replaceLines(List.of(
                line(beanId, "18", 0),
                line(milkId, "180", 1),
                line(syrupId, "20", 2),
                line(iceId, "120", 3),
                line(cupId, "1", 4)));
        return v;
    }

    private static RecipeVersionLine line(UUID ingredient, String qty, int order) {
        return RecipeVersionLine.create(ingredient, new BigDecimal(qty), BigDecimal.ZERO,
                null, RecipeScalingRule.FIXED, order, false, null);
    }

    private Product activeProduct(StockMode mode) {
        Product p = Product.create(UUID.randomUUID(), "P" + productId, "Latte", null,
                new BigDecimal("10"), "USD", 5, new BigDecimal("0.1"), true, true, null);
        setField(p, "stockMode", mode);
        return p;
    }

    private static Ingredient ingredient(UUID id, String code, String name, UUID baseUnitId) {
        Ingredient i = Ingredient.create(code, name, baseUnitId, BigDecimal.ZERO, true, false, null, true);
        setField(i, "id", id);
        return i;
    }

    private static UnitOfMeasure unit(UUID id, String code, UnitType type, BigDecimal factor) {
        UnitOfMeasure u = UnitOfMeasure.create(code, code, type, 4, factor, true, true);
        setField(u, "id", id);
        return u;
    }

    private static CustomizationOption option(UUID id, String code, InventoryEffectType type,
            UUID source, UUID target, BigDecimal qty, BigDecimal mult) {
        try {
            var ctor = CustomizationOption.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            CustomizationOption o = ctor.newInstance();
            setFieldOn(o, CustomizationOption.class, "id", id);
            setFieldOn(o, CustomizationOption.class, "groupId", GROUP);
            setFieldOn(o, CustomizationOption.class, "code", code);
            setFieldOn(o, CustomizationOption.class, "name", code);
            setFieldOn(o, CustomizationOption.class, "additionalPrice", BigDecimal.ZERO);
            setFieldOn(o, CustomizationOption.class, "displayOrder", 0);
            setFieldOn(o, CustomizationOption.class, "active", true);
            o.applyInventoryEffect(type, source, target, qty, mult, null);
            return o;
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void setField(Object target, String field, Object value) {
        setFieldOn(target, target.getClass(), field, value);
    }

    private static void setFieldOn(Object target, Class<?> owner, String field, Object value) {
        try {
            Field f = owner.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
