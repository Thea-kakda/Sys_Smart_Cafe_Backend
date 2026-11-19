package com.smartcoffee.modules.production.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.modules.catalog.domain.Product;
import com.smartcoffee.modules.catalog.domain.ProductVariant;
import com.smartcoffee.modules.catalog.persistence.ProductRepository;
import com.smartcoffee.modules.catalog.persistence.ProductVariantRepository;
import com.smartcoffee.modules.inventory.domain.Ingredient;
import com.smartcoffee.modules.inventory.domain.UnitOfMeasure;
import com.smartcoffee.modules.inventory.persistence.IngredientRepository;
import com.smartcoffee.modules.inventory.persistence.UnitOfMeasureRepository;
import com.smartcoffee.modules.recipe.application.RecipeLineNormalizer;
import com.smartcoffee.modules.recipe.application.RecipeVersionResolver;
import com.smartcoffee.modules.recipe.domain.RecipeStatus;
import com.smartcoffee.modules.recipe.domain.RecipeVersion;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Focused unit coverage for {@link ProductionMaterialPlanner} (Slice 5A §5,
 * Phase 5A.1 material snapshot contract). Repositories and the recipe
 * resolver/normalizer collaborators are mocked so the test exercises only
 * the arithmetic + Variant→Product→error output-mapping cascade and the
 * planning failure codes declared in spec §19. No database is opened.
 *
 * <p>Cases mirror spec §24 planner-test list:
 * <ol>
 *   <li>Zero / negative / null planned quantity fails with
 *       {@code PRODUCTION_OUTPUT_QUANTITY_INVALID}.</li>
 *   <li>Missing Product / Variant raises the corresponding stable code.</li>
 *   <li>Variant belonging to a different Product is rejected with
 *       {@code PRODUCTION_ORDER_TARGET_MISMATCH}.</li>
 *   <li>No recipe resolved → {@code PRODUCTION_RECIPE_REQUIRED}.</li>
 *   <li>Non-ACTIVE recipe → {@code PRODUCTION_RECIPE_NOT_ACTIVE}.</li>
 *   <li>Output missing on both Variant and Product → {@code
 *       PRODUCTION_OUTPUT_ITEM_REQUIRED}.</li>
 *   <li>Output Variant override wins over Product fallback (Decision 1 rule 10).</li>
 *   <li>Output Product fallback applies when the Variant has no mapping.</li>
 *   <li>Output ingredient missing / inactive → {@code
 *       PRODUCTION_OUTPUT_ITEM_MISMATCH}.</li>
 *   <li>Scaled demand = perUnit × plannedOutput with 4 dp CEILING rounding.</li>
 *   <li>Snapshot rows carry the ingredient's base-unit code.</li>
 * </ol>
 */
class ProductionMaterialPlannerTest {

    private static final UUID PRODUCT_ID = UUID.randomUUID();
    private static final UUID VARIANT_ID = UUID.randomUUID();
    private static final UUID RECIPE_VERSION_ID = UUID.randomUUID();
    private static final UUID BEANS = UUID.randomUUID();
    private static final UUID MILK = UUID.randomUUID();
    private static final UUID OUTPUT_ITEM = UUID.randomUUID();
    private static final UUID GRAM = UUID.randomUUID();
    private static final UUID EACH = UUID.randomUUID();

    private RecipeVersionResolver resolver;
    private RecipeLineNormalizer normalizer;
    private ProductRepository products;
    private ProductVariantRepository variants;
    private IngredientRepository ingredients;
    private UnitOfMeasureRepository units;
    private ProductionMaterialPlanner planner;

    @BeforeEach
    void wire() {
        resolver = mock(RecipeVersionResolver.class);
        normalizer = mock(RecipeLineNormalizer.class);
        products = mock(ProductRepository.class);
        variants = mock(ProductVariantRepository.class);
        ingredients = mock(IngredientRepository.class);
        units = mock(UnitOfMeasureRepository.class);
        planner = new ProductionMaterialPlanner(resolver, normalizer, products, variants, ingredients, units);
    }

    // ------------------------------------------------------------- fixtures
    private Product productWithOutput(UUID outputId) {
        Product p = mock(Product.class);
        when(p.id()).thenReturn(PRODUCT_ID);
        when(p.outputIngredientId()).thenReturn(outputId);
        return p;
    }

    private ProductVariant variantWithOutput(UUID outputId) {
        ProductVariant v = mock(ProductVariant.class);
        when(v.productId()).thenReturn(PRODUCT_ID);
        when(v.outputIngredientId()).thenReturn(outputId);
        return v;
    }

    private RecipeVersion activeRecipe() {
        RecipeVersion rv = mock(RecipeVersion.class);
        when(rv.id()).thenReturn(RECIPE_VERSION_ID);
        when(rv.versionNumber()).thenReturn(2);
        when(rv.status()).thenReturn(RecipeStatus.ACTIVE);
        return rv;
    }

    private UnitOfMeasure unit(UUID id, String code) {
        UnitOfMeasure u = mock(UnitOfMeasure.class);
        when(u.id()).thenReturn(id);
        when(u.code()).thenReturn(code);
        return u;
    }

    private Ingredient ingredient(UUID id, UUID baseUnitId, boolean active) {
        Ingredient i = mock(Ingredient.class);
        when(i.id()).thenReturn(id);
        when(i.baseUnitId()).thenReturn(baseUnitId);
        when(i.active()).thenReturn(active);
        return i;
    }

    // ----------------------------------------------------------- behaviour
    // Every helper mock is hoisted into a local variable BEFORE the outer
    // when(...).thenReturn(...) chain starts — Mockito's strict stubbing
    // otherwise reports UnfinishedStubbing when a nested when() is invoked
    // while an outer stubbing target is still open.
    @Test
    void rejectsNonPositivePlannedQuantity() {
        assertCode("PRODUCTION_OUTPUT_QUANTITY_INVALID",
                () -> planner.plan(PRODUCT_ID, null, BigDecimal.ZERO));
        assertCode("PRODUCTION_OUTPUT_QUANTITY_INVALID",
                () -> planner.plan(PRODUCT_ID, null, new BigDecimal("-5")));
        assertCode("PRODUCTION_OUTPUT_QUANTITY_INVALID",
                () -> planner.plan(PRODUCT_ID, null, null));
    }

    @Test
    void rejectsMissingProduct() {
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.empty());
        assertCode("PRODUCT_NOT_FOUND", () -> planner.plan(PRODUCT_ID, null, BigDecimal.ONE));
    }

    @Test
    void rejectsMissingVariant() {
        Product p = productWithOutput(OUTPUT_ITEM);
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(p));
        when(variants.findByIdAndDeletedAtIsNull(VARIANT_ID)).thenReturn(Optional.empty());
        assertCode("VARIANT_NOT_FOUND", () -> planner.plan(PRODUCT_ID, VARIANT_ID, BigDecimal.ONE));
    }

    @Test
    void rejectsVariantBelongingToAnotherProduct() {
        Product p = productWithOutput(OUTPUT_ITEM);
        ProductVariant foreign = mock(ProductVariant.class);
        when(foreign.productId()).thenReturn(UUID.randomUUID()); // different product
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(p));
        when(variants.findByIdAndDeletedAtIsNull(VARIANT_ID)).thenReturn(Optional.of(foreign));
        assertCode("PRODUCTION_ORDER_TARGET_MISMATCH",
                () -> planner.plan(PRODUCT_ID, VARIANT_ID, BigDecimal.ONE));
    }

    @Test
    void rejectsWhenNoRecipeResolved() {
        Product p = productWithOutput(OUTPUT_ITEM);
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(p));
        when(resolver.resolve(PRODUCT_ID, null))
                .thenReturn(new RecipeVersionResolver.Resolved(RecipeVersionResolver.Source.NONE, null));
        assertCode("PRODUCTION_RECIPE_REQUIRED", () -> planner.plan(PRODUCT_ID, null, BigDecimal.ONE));
    }

    @Test
    void rejectsNonActiveRecipeVersion() {
        Product p = productWithOutput(OUTPUT_ITEM);
        RecipeVersion draft = mock(RecipeVersion.class);
        when(draft.status()).thenReturn(RecipeStatus.DRAFT);
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(p));
        when(resolver.resolve(PRODUCT_ID, null))
                .thenReturn(new RecipeVersionResolver.Resolved(RecipeVersionResolver.Source.PRODUCT, draft));
        assertCode("PRODUCTION_RECIPE_NOT_ACTIVE", () -> planner.plan(PRODUCT_ID, null, BigDecimal.ONE));
    }

    @Test
    void rejectsWhenNeitherVariantNorProductDeclaresOutput() {
        // No variantId → only product pointer is consulted; both null → required.
        Product p = productWithOutput(null);
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(p));
        RecipeVersion rv = activeRecipe();
        when(resolver.resolve(PRODUCT_ID, null))
                .thenReturn(new RecipeVersionResolver.Resolved(RecipeVersionResolver.Source.PRODUCT, rv));
        when(normalizer.normalize(rv)).thenReturn(List.of(
                new RecipeLineNormalizer.NormalizedLine(BEANS, new BigDecimal("0.018"), 0, false)));
        Ingredient beans = ingredient(BEANS, GRAM, true);
        UnitOfMeasure gram = unit(GRAM, "G");
        when(ingredients.findAllById(any())).thenReturn(List.of(beans));
        when(units.findAllById(any())).thenReturn(List.of(gram));
        assertCode("PRODUCTION_OUTPUT_ITEM_REQUIRED", () -> planner.plan(PRODUCT_ID, null, BigDecimal.ONE));
    }

    @Test
    void variantOutputOverrideWinsOverProductFallback() {
        UUID variantOutput = UUID.randomUUID();
        UUID productOutput = UUID.randomUUID();
        // Variant declares its own; Product declares a different one — variant wins.
        Product p = productWithOutput(productOutput);
        ProductVariant v = variantWithOutput(variantOutput);
        RecipeVersion rv = activeRecipe();
        Ingredient beans = ingredient(BEANS, GRAM, true);
        UnitOfMeasure gram = unit(GRAM, "G");
        Ingredient variantMapped = ingredient(variantOutput, EACH, true);
        UnitOfMeasure each = unit(EACH, "EA");
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(p));
        when(variants.findByIdAndDeletedAtIsNull(VARIANT_ID)).thenReturn(Optional.of(v));
        when(resolver.resolve(PRODUCT_ID, VARIANT_ID))
                .thenReturn(new RecipeVersionResolver.Resolved(RecipeVersionResolver.Source.VARIANT, rv));
        when(normalizer.normalize(rv)).thenReturn(List.of(
                new RecipeLineNormalizer.NormalizedLine(BEANS, new BigDecimal("0.018"), 0, false)));
        when(ingredients.findAllById(any())).thenReturn(List.of(beans));
        when(units.findAllById(any())).thenReturn(List.of(gram));
        when(ingredients.findById(variantOutput)).thenReturn(Optional.of(variantMapped));
        when(units.findById(EACH)).thenReturn(Optional.of(each));

        var plan = planner.plan(PRODUCT_ID, VARIANT_ID, new BigDecimal("10"));
        assertEquals(variantOutput, plan.outputInventoryItemId(),
                "Decision 1 rule 10: variant override must beat product fallback");
    }

    @Test
    void productOutputAppliesWhenVariantHasNone() {
        UUID productOutput = UUID.randomUUID();
        Product p = productWithOutput(productOutput);
        ProductVariant v = variantWithOutput(null); // variant override absent
        RecipeVersion rv = activeRecipe();
        Ingredient beans = ingredient(BEANS, GRAM, true);
        UnitOfMeasure gram = unit(GRAM, "G");
        Ingredient productMapped = ingredient(productOutput, EACH, true);
        UnitOfMeasure each = unit(EACH, "EA");
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(p));
        when(variants.findByIdAndDeletedAtIsNull(VARIANT_ID)).thenReturn(Optional.of(v));
        when(resolver.resolve(PRODUCT_ID, VARIANT_ID))
                .thenReturn(new RecipeVersionResolver.Resolved(RecipeVersionResolver.Source.VARIANT, rv));
        when(normalizer.normalize(rv)).thenReturn(List.of(
                new RecipeLineNormalizer.NormalizedLine(BEANS, new BigDecimal("0.018"), 0, false)));
        when(ingredients.findAllById(any())).thenReturn(List.of(beans));
        when(units.findAllById(any())).thenReturn(List.of(gram));
        when(ingredients.findById(productOutput)).thenReturn(Optional.of(productMapped));
        when(units.findById(EACH)).thenReturn(Optional.of(each));

        var plan = planner.plan(PRODUCT_ID, VARIANT_ID, new BigDecimal("10"));
        assertEquals(productOutput, plan.outputInventoryItemId(),
                "absent variant override must fall through to product pointer");
    }

    @Test
    void rejectsInactiveOutputIngredient() {
        Product p = productWithOutput(OUTPUT_ITEM);
        RecipeVersion rv = activeRecipe();
        Ingredient beans = ingredient(BEANS, GRAM, true);
        UnitOfMeasure gram = unit(GRAM, "G");
        Ingredient inactiveOutput = ingredient(OUTPUT_ITEM, EACH, false);
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(p));
        when(resolver.resolve(PRODUCT_ID, null))
                .thenReturn(new RecipeVersionResolver.Resolved(RecipeVersionResolver.Source.PRODUCT, rv));
        when(normalizer.normalize(rv)).thenReturn(List.of(
                new RecipeLineNormalizer.NormalizedLine(BEANS, new BigDecimal("0.018"), 0, false)));
        when(ingredients.findAllById(any())).thenReturn(List.of(beans));
        when(units.findAllById(any())).thenReturn(List.of(gram));
        when(ingredients.findById(OUTPUT_ITEM)).thenReturn(Optional.of(inactiveOutput));
        assertCode("PRODUCTION_OUTPUT_ITEM_MISMATCH", () -> planner.plan(PRODUCT_ID, null, BigDecimal.ONE));
    }

    @Test
    void rejectsMissingOutputIngredient() {
        Product p = productWithOutput(OUTPUT_ITEM);
        RecipeVersion rv = activeRecipe();
        Ingredient beans = ingredient(BEANS, GRAM, true);
        UnitOfMeasure gram = unit(GRAM, "G");
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(p));
        when(resolver.resolve(PRODUCT_ID, null))
                .thenReturn(new RecipeVersionResolver.Resolved(RecipeVersionResolver.Source.PRODUCT, rv));
        when(normalizer.normalize(rv)).thenReturn(List.of(
                new RecipeLineNormalizer.NormalizedLine(BEANS, new BigDecimal("0.018"), 0, false)));
        when(ingredients.findAllById(any())).thenReturn(List.of(beans));
        when(units.findAllById(any())).thenReturn(List.of(gram));
        when(ingredients.findById(OUTPUT_ITEM)).thenReturn(Optional.empty());
        assertCode("PRODUCTION_OUTPUT_ITEM_MISMATCH", () -> planner.plan(PRODUCT_ID, null, BigDecimal.ONE));
    }

    @Test
    void scalesPerUnitDemandWithCeilingRoundingAndSnapshotsBaseUnitCode() {
        // Product mapping present, no variant in scope.
        Product p = productWithOutput(OUTPUT_ITEM);
        RecipeVersion rv = activeRecipe();
        Ingredient beans = ingredient(BEANS, GRAM, true);
        Ingredient milk = ingredient(MILK, GRAM, true);
        UnitOfMeasure gram = unit(GRAM, "G");
        Ingredient output = ingredient(OUTPUT_ITEM, EACH, true);
        UnitOfMeasure each = unit(EACH, "EA");
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(p));
        when(resolver.resolve(PRODUCT_ID, null))
                .thenReturn(new RecipeVersionResolver.Resolved(RecipeVersionResolver.Source.PRODUCT, rv));
        when(normalizer.normalize(rv)).thenReturn(List.of(
                new RecipeLineNormalizer.NormalizedLine(BEANS, new BigDecimal("0.018"), 0, false),
                new RecipeLineNormalizer.NormalizedLine(MILK, new BigDecimal("0.180"), 1, false)));
        when(ingredients.findAllById(any())).thenReturn(List.of(beans, milk));
        when(units.findAllById(any())).thenReturn(List.of(gram));
        when(ingredients.findById(OUTPUT_ITEM)).thenReturn(Optional.of(output));
        when(units.findById(EACH)).thenReturn(Optional.of(each));

        var plan = planner.plan(PRODUCT_ID, null, new BigDecimal("7"));

        assertEquals(rv, plan.recipeVersion());
        assertEquals(2, plan.materials().size());
        // 0.018 * 7 = 0.126, scaled to 4 dp CEILING = 0.1260
        assertEquals(0, new BigDecimal("0.1260").compareTo(plan.materials().get(0).quantityPlannedBase()));
        // 0.180 * 7 = 1.260 → 1.2600
        assertEquals(0, new BigDecimal("1.2600").compareTo(plan.materials().get(1).quantityPlannedBase()));
        assertEquals("G", plan.materials().get(0).baseUnitCode());
        assertEquals("G", plan.materials().get(1).baseUnitCode());
        assertEquals(BEANS, plan.materials().get(0).inventoryItemId());
        assertEquals(MILK, plan.materials().get(1).inventoryItemId());
        assertEquals(0, plan.materials().get(0).displayOrder());
        assertEquals(1, plan.materials().get(1).displayOrder());

        // Output row: 7 EA — planned quantity is preserved, base unit is EA.
        assertEquals(OUTPUT_ITEM, plan.output().outputInventoryItemId());
        assertEquals(0, new BigDecimal("7").compareTo(plan.output().plannedQuantity()));
        assertEquals("EA", plan.output().unitCode());
    }

    @Test
    void ceilingRoundingFoldsSubBaseUnitDemandUp() {
        Product p = productWithOutput(OUTPUT_ITEM);
        RecipeVersion rv = activeRecipe();
        Ingredient beans = ingredient(BEANS, GRAM, true);
        UnitOfMeasure gram = unit(GRAM, "G");
        Ingredient output = ingredient(OUTPUT_ITEM, EACH, true);
        UnitOfMeasure each = unit(EACH, "EA");
        when(products.findById(PRODUCT_ID)).thenReturn(Optional.of(p));
        when(resolver.resolve(PRODUCT_ID, null))
                .thenReturn(new RecipeVersionResolver.Resolved(RecipeVersionResolver.Source.PRODUCT, rv));
        when(normalizer.normalize(rv)).thenReturn(List.of(
                new RecipeLineNormalizer.NormalizedLine(BEANS, new BigDecimal("0.00005"), 0, false)));
        when(ingredients.findAllById(any())).thenReturn(List.of(beans));
        when(units.findAllById(any())).thenReturn(List.of(gram));
        when(ingredients.findById(OUTPUT_ITEM)).thenReturn(Optional.of(output));
        when(units.findById(EACH)).thenReturn(Optional.of(each));

        var plan = planner.plan(PRODUCT_ID, null, new BigDecimal("3"));
        // 0.00005 * 3 = 0.00015 → CEILING(scale=4) = 0.0002, never 0.0001 (floor) or 0 (drop).
        assertEquals(0, new BigDecimal("0.0002").compareTo(plan.materials().get(0).quantityPlannedBase()),
                "CEILING prevents rounding-induced shortfall on the shop floor");
    }

    // ------------------------------------------------------------------ util
    private void assertCode(String expected, Runnable body) {
        ValidationException ex = assertThrows(ValidationException.class, body::run);
        assertEquals(expected, ex.code(), "stable error code per Slice 5A §19");
    }
}
