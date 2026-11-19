package com.smartcoffee.modules.recipe.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.modules.inventory.domain.Ingredient;
import com.smartcoffee.modules.inventory.domain.UnitOfMeasure;
import com.smartcoffee.modules.inventory.domain.UnitType;
import com.smartcoffee.modules.inventory.persistence.IngredientRepository;
import com.smartcoffee.modules.inventory.persistence.UnitOfMeasureRepository;
import com.smartcoffee.modules.recipe.application.RecipeLineNormalizer.NormalizedLine;
import com.smartcoffee.modules.recipe.domain.RecipeScalingRule;
import com.smartcoffee.modules.recipe.domain.RecipeVersion;
import com.smartcoffee.modules.recipe.domain.RecipeVersionLine;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit coverage for Slice 2B base-line normalization: FIXED vs PROPORTIONAL_TO_YIELD
 * scaling, wastage, yield validation, decimal-safe unit conversion (single
 * dimension only), duplicate merge and immutability of the input recipe. Quantities
 * are asserted at the per-single-sellable-unit level (product-quantity scaling and
 * final rounding happen later in {@code ConsumptionPlanService}).
 */
class RecipeLineNormalizerTest {

    private static final UUID GRAM = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final UUID KILOGRAM = UUID.fromString("60000000-0000-0000-0000-000000000002");
    private static final UUID MILLILITER = UUID.fromString("60000000-0000-0000-0000-000000000003");

    private IngredientRepository ingredients;
    private UnitOfMeasureRepository units;
    private RecipeLineNormalizer normalizer;

    private final UUID beanId = UUID.randomUUID();
    private final UUID milkId = UUID.randomUUID();

    @BeforeEach
    void wire() {
        ingredients = mock(IngredientRepository.class);
        units = mock(UnitOfMeasureRepository.class);
        normalizer = new RecipeLineNormalizer(ingredients, units);

        UnitOfMeasure gram = UnitOfMeasure.create("GRAM", "Gram", UnitType.MASS, 4, BigDecimal.ONE, true, true);
        UnitOfMeasure kg = UnitOfMeasure.create("KILOGRAM", "Kilogram", UnitType.MASS, 4,
                new BigDecimal("1000"), false, true);
        UnitOfMeasure ml = UnitOfMeasure.create("MILLILITER", "Milliliter", UnitType.VOLUME, 4,
                BigDecimal.ONE, true, true);
        setUnitId(gram, GRAM);
        setUnitId(kg, KILOGRAM);
        setUnitId(ml, MILLILITER);
        when(units.findAllById(anyIterable())).thenReturn(List.of(gram, kg, ml));

        when(ingredients.findAllById(anyIterable())).thenReturn(List.of(
                ingredient("BEANS", "Coffee Beans", GRAM, beanId),
                ingredient("MILK", "Fresh Milk", MILLILITER, milkId)));
    }

    @Test
    void fixedLineMatchesCheckoutDemandPerUnit() {
        RecipeVersion v = version(BigDecimal.ONE,
                RecipeVersionLine.create(beanId, new BigDecimal("18"), BigDecimal.ZERO,
                        null, RecipeScalingRule.FIXED, 0, false, null));
        List<NormalizedLine> lines = normalizer.normalize(v);
        assertEquals(1, lines.size());
        // FIXED per-unit == demand(1) == 18 g (null unit -> already base -> identity).
        assertEquals(0, new BigDecimal("18").compareTo(lines.get(0).perUnit()));
        assertEquals(0, lines.get(0).perUnit().compareTo(v.lines().get(0).demand(1)));
    }

    @Test
    void wastageInflatesFixedDemand() {
        RecipeVersion v = version(BigDecimal.ONE,
                RecipeVersionLine.create(beanId, new BigDecimal("100"), new BigDecimal("10"),
                        null, RecipeScalingRule.FIXED, 0, false, null));
        // 100 * (1 + 10%) = 110
        assertEquals(0, new BigDecimal("110").compareTo(normalizer.normalize(v).get(0).perUnit()));
    }

    @Test
    void proportionalLineDividesByYield() {
        // Batch uses 500 g butter for 10 croissants -> 50 g per croissant.
        RecipeVersion v = version(new BigDecimal("10"),
                RecipeVersionLine.create(beanId, new BigDecimal("500"), BigDecimal.ZERO,
                        GRAM, RecipeScalingRule.PROPORTIONAL_TO_YIELD, 0, false, null));
        assertEquals(0, new BigDecimal("50").compareTo(normalizer.normalize(v).get(0).perUnit()));
    }

    @Test
    void decimalYieldIsHandledDecimalSafe() {
        RecipeVersion v = version(new BigDecimal("2.5"),
                RecipeVersionLine.create(beanId, new BigDecimal("10"), BigDecimal.ZERO,
                        null, RecipeScalingRule.PROPORTIONAL_TO_YIELD, 0, false, null));
        // 10 / 2.5 = 4
        assertEquals(0, new BigDecimal("4").compareTo(normalizer.normalize(v).get(0).perUnit()));
    }

    @Test
    void nonPositiveYieldIsRejected() {
        RecipeVersion v = version(BigDecimal.ONE,
                RecipeVersionLine.create(beanId, new BigDecimal("18"), BigDecimal.ZERO,
                        null, RecipeScalingRule.FIXED, 0, false, null));
        setYield(v, BigDecimal.ZERO);
        ValidationException ex = assertThrows(ValidationException.class, () -> normalizer.normalize(v));
        assertEquals("INVALID_RECIPE_YIELD", ex.code());
    }

    @Test
    void convertsAuthoredUnitToIngredientBaseUnit() {
        // 0.5 kg authored in KILOGRAM -> 500 GRAM base.
        RecipeVersion v = version(BigDecimal.ONE,
                RecipeVersionLine.create(beanId, new BigDecimal("0.5"), BigDecimal.ZERO,
                        KILOGRAM, RecipeScalingRule.FIXED, 0, false, null));
        assertEquals(0, new BigDecimal("500").compareTo(normalizer.normalize(v).get(0).perUnit()));
    }

    @Test
    void rejectsCrossDimensionConversion() {
        // Milk base unit is VOLUME; authoring it in a MASS unit is invalid.
        RecipeVersion v = version(BigDecimal.ONE,
                RecipeVersionLine.create(milkId, new BigDecimal("1"), BigDecimal.ZERO,
                        KILOGRAM, RecipeScalingRule.FIXED, 0, false, null));
        ValidationException ex = assertThrows(ValidationException.class, () -> normalizer.normalize(v));
        assertEquals("INCOMPATIBLE_RECIPE_UNIT", ex.code());
    }

    @Test
    void duplicateLinesMergeDeterministicallyKeepingEarliestOrderAndOptionalAnd() {
        RecipeVersion v = version(BigDecimal.ONE,
                RecipeVersionLine.create(beanId, new BigDecimal("10"), BigDecimal.ZERO,
                        null, RecipeScalingRule.FIXED, 3, true, null),
                RecipeVersionLine.create(beanId, new BigDecimal("5"), BigDecimal.ZERO,
                        null, RecipeScalingRule.FIXED, 1, false, null));
        List<NormalizedLine> lines = normalizer.normalize(v);
        assertEquals(1, lines.size());
        assertEquals(0, new BigDecimal("15").compareTo(lines.get(0).perUnit()));
        assertEquals(1, lines.get(0).displayOrder(), "earliest display order wins");
        assertFalse(lines.get(0).optional(), "optional ANDs across merged lines");
    }

    @Test
    void inputRecipeLinesAreNotMutated() {
        RecipeVersion v = version(BigDecimal.ONE,
                RecipeVersionLine.create(beanId, new BigDecimal("18"), BigDecimal.ZERO,
                        null, RecipeScalingRule.FIXED, 0, false, null));
        BigDecimal before = v.lines().get(0).quantityRequiredBase();
        normalizer.normalize(v);
        assertEquals(0, before.compareTo(v.lines().get(0).quantityRequiredBase()));
        assertEquals(1, v.lines().size(), "normalizer never adds or removes lines");
    }

    // ------------------------------------------------------------- helpers

    private RecipeVersion version(BigDecimal yield, RecipeVersionLine... lines) {
        RecipeVersion v = RecipeVersion.createDraft(UUID.randomUUID(), 1, yield, null, "t", null);
        v.replaceLines(List.of(lines));
        return v;
    }

    private static Ingredient ingredient(String code, String name, UUID baseUnitId, UUID id) {
        Ingredient i = Ingredient.create(code, name, baseUnitId, BigDecimal.ZERO, true, false, null, true);
        setField(i, Ingredient.class, "id", id);
        return i;
    }

    private static void setUnitId(UnitOfMeasure u, UUID id) {
        setField(u, UnitOfMeasure.class, "id", id);
    }

    private static void setYield(RecipeVersion v, BigDecimal yield) {
        setField(v, RecipeVersion.class, "yieldQuantity", yield);
    }

    private static void setField(Object target, Class<?> owner, String field, Object value) {
        try {
            Field f = owner.getDeclaredField(field);
            f.setAccessible(true);
            f.set(target, value);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
