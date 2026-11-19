package com.smartcoffee.modules.catalog.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.when;

import com.smartcoffee.modules.catalog.domain.CustomizationOption;
import com.smartcoffee.modules.catalog.domain.InventoryEffectType;
import com.smartcoffee.modules.catalog.persistence.CustomizationOptionRepository;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Deterministic unit tests for {@link InventoryEffectCalculator}. Spec section
 * C requires proof that:
 * <ul>
 *   <li>ADD contributes the configured quantity of the target ingredient.</li>
 *   <li>REMOVE reduces only the selected ingredient.</li>
 *   <li>REPLACE swaps Fresh Milk for Oat Milk while preserving quantity.</li>
 *   <li>MULTIPLY scales 20 ml Sugar Syrup to 0/10/20/30 ml at 0% / 50% / 100%
 *       / 150% and never touches coffee beans, milk, ice, cups or lids.</li>
 *   <li>Repeated calculations are deterministic.</li>
 * </ul>
 */
class InventoryEffectCalculatorTest {

    // Well-known ingredient ids for readability.
    private static final UUID SUGAR_SYRUP = UUID.randomUUID();
    private static final UUID COFFEE_BEANS = UUID.randomUUID();
    private static final UUID FRESH_MILK    = UUID.randomUUID();
    private static final UUID OAT_MILK      = UUID.randomUUID();
    private static final UUID ICE           = UUID.randomUUID();
    private static final UUID CUP           = UUID.randomUUID();
    private static final UUID LID           = UUID.randomUUID();

    private static CustomizationOption option(UUID id, InventoryEffectType t, UUID targetIngredient,
            BigDecimal qty, BigDecimal mult, UUID replaces) throws Exception {
        return option(id, t, null, targetIngredient, qty, mult, replaces);
    }

    private static CustomizationOption option(UUID id, InventoryEffectType t,
            UUID sourceIngredient, UUID targetIngredient,
            BigDecimal qty, BigDecimal mult, UUID replaces) throws Exception {
        var ctor = CustomizationOption.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        CustomizationOption o = ctor.newInstance();
        set(o, "id", id);
        set(o, "groupId", UUID.randomUUID());
        set(o, "code", "o-" + id);
        set(o, "name", "opt");
        set(o, "additionalPrice", BigDecimal.ZERO);
        set(o, "displayOrder", 0);
        set(o, "active", true);
        set(o, "inventoryEffectType", t);
        set(o, "sourceIngredientId", sourceIngredient);
        set(o, "targetIngredientId", targetIngredient);
        set(o, "effectQuantity", qty);
        set(o, "effectMultiplier", mult);
        set(o, "replacesOptionId", replaces);
        return o;
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Map<UUID, BigDecimal> baseRecipe() {
        Map<UUID, BigDecimal> base = new LinkedHashMap<>();
        base.put(COFFEE_BEANS, new BigDecimal("18"));
        base.put(SUGAR_SYRUP, new BigDecimal("20"));   // ml
        base.put(FRESH_MILK, new BigDecimal("150"));   // ml
        base.put(ICE, new BigDecimal("120"));
        base.put(CUP, new BigDecimal("1"));
        base.put(LID, new BigDecimal("1"));
        return base;
    }

    private InventoryEffectCalculator calcWith(CustomizationOption... opts) {
        CustomizationOptionRepository repo = Mockito.mock(CustomizationOptionRepository.class);
        when(repo.findAllById(Mockito.<Collection<UUID>>any())).thenReturn(List.of(opts));
        return new InventoryEffectCalculator(repo);
    }

    @Test
    void addContributesTargetQuantity() throws Exception {
        UUID extraShot = UUID.randomUUID();
        var opt = option(extraShot, InventoryEffectType.ADD, COFFEE_BEANS, new BigDecimal("9"), null, null);
        var calc = calcWith(opt);
        Map<UUID, BigDecimal> out = calc.calculate(baseRecipe(), List.of(extraShot));
        // 18 base + 9 extra = 27
        assertEquals(0, new BigDecimal("27").compareTo(out.get(COFFEE_BEANS)));
        // Everything else unchanged
        assertEquals(out.get(SUGAR_SYRUP), new BigDecimal("20"));
        assertEquals(out.get(FRESH_MILK), new BigDecimal("150"));
    }

    @Test
    void removeReducesOnlySelectedIngredient() throws Exception {
        UUID noIce = UUID.randomUUID();
        var opt = option(noIce, InventoryEffectType.REMOVE, ICE, new BigDecimal("120"), null, null);
        var calc = calcWith(opt);
        Map<UUID, BigDecimal> out = calc.calculate(baseRecipe(), List.of(noIce));
        assertEquals(0, out.get(ICE).compareTo(BigDecimal.ZERO));
        // Base map is not mutated (spec: "must not directly mutate the master recipe").
        assertEquals(0, new BigDecimal("18").compareTo(out.get(COFFEE_BEANS)));
        assertEquals(0, new BigDecimal("1").compareTo(out.get(CUP)));
    }

    @Test
    void replaceSwapsIngredientPreservingCalculatedSourceAmount() throws Exception {
        UUID freshOpt = UUID.randomUUID();
        UUID oatOpt = UUID.randomUUID();
        // V041 model: REPLACE carries source + target directly. The base recipe
        // contains 150 ml Fresh Milk; FRESH contributes nothing (its own
        // option is NONE — the base is what would be consumed if the customer
        // did not select an alternative); OAT declares
        //   sourceIngredientId = FRESH_MILK, targetIngredientId = OAT_MILK,
        //   effectQuantity = null.
        // Selecting OAT should preserve the full calculated Fresh Milk amount
        // (150 ml) and re-attribute it to Oat Milk.
        var fresh = option(freshOpt, InventoryEffectType.NONE, null, null, null, null, null);
        var oat = option(oatOpt, InventoryEffectType.REPLACE, FRESH_MILK, OAT_MILK, null, null, null);
        var calc = calcWith(fresh, oat);
        Map<UUID, BigDecimal> out = calc.calculate(baseRecipe(), List.of(freshOpt, oatOpt));
        assertEquals(0, BigDecimal.ZERO.compareTo(out.get(FRESH_MILK)));
        assertEquals(0, new BigDecimal("150").compareTo(out.get(OAT_MILK)));
    }

    @Test
    void replaceWithExplicitQuantityOverridesPreservedAmount() throws Exception {
        UUID oatOpt = UUID.randomUUID();
        // Explicit replacement quantity: 200 ml Oat for 150 ml base Fresh.
        var oat = option(oatOpt, InventoryEffectType.REPLACE, FRESH_MILK, OAT_MILK,
                new BigDecimal("200"), null, null);
        Map<UUID, BigDecimal> out = calcWith(oat).calculate(baseRecipe(), List.of(oatOpt));
        assertEquals(0, BigDecimal.ZERO.compareTo(out.get(FRESH_MILK)));
        assertEquals(0, new BigDecimal("200").compareTo(out.get(OAT_MILK)));
    }

    @Test
    void multiplySugarPercentsProduceSpecValues() throws Exception {
        UUID opt0 = UUID.randomUUID();
        UUID opt50 = UUID.randomUUID();
        UUID opt100 = UUID.randomUUID();
        UUID opt150 = UUID.randomUUID();
        var m0 = option(opt0, InventoryEffectType.MULTIPLY, SUGAR_SYRUP, null, new BigDecimal("0.00"), null);
        var m50 = option(opt50, InventoryEffectType.MULTIPLY, SUGAR_SYRUP, null, new BigDecimal("0.50"), null);
        var m100 = option(opt100, InventoryEffectType.MULTIPLY, SUGAR_SYRUP, null, new BigDecimal("1.00"), null);
        var m150 = option(opt150, InventoryEffectType.MULTIPLY, SUGAR_SYRUP, null, new BigDecimal("1.50"), null);

        var calc = calcWith(m0, m50, m100, m150);
        Map<UUID, BigDecimal> base = baseRecipe();

        assertEquals(0, calc.calculate(base, List.of(opt0)).get(SUGAR_SYRUP).compareTo(new BigDecimal("0")));
        assertEquals(0, calc.calculate(base, List.of(opt50)).get(SUGAR_SYRUP).compareTo(new BigDecimal("10")));
        assertEquals(0, calc.calculate(base, List.of(opt100)).get(SUGAR_SYRUP).compareTo(new BigDecimal("20")));
        assertEquals(0, calc.calculate(base, List.of(opt150)).get(SUGAR_SYRUP).compareTo(new BigDecimal("30")));

        // None of the other ingredients are touched by MULTIPLY on syrup.
        Map<UUID, BigDecimal> at50 = calc.calculate(base, List.of(opt50));
        assertEquals(0, new BigDecimal("18").compareTo(at50.get(COFFEE_BEANS)));
        assertEquals(0, new BigDecimal("150").compareTo(at50.get(FRESH_MILK)));
        assertEquals(0, new BigDecimal("120").compareTo(at50.get(ICE)));
        assertEquals(0, new BigDecimal("1").compareTo(at50.get(CUP)));
        assertEquals(0, new BigDecimal("1").compareTo(at50.get(LID)));
    }

    @Test
    void repeatedCalculationsAreDeterministic() throws Exception {
        UUID opt = UUID.randomUUID();
        var m = option(opt, InventoryEffectType.MULTIPLY, SUGAR_SYRUP, null, new BigDecimal("0.15"), null);
        var calc = calcWith(m);
        Map<UUID, BigDecimal> base = baseRecipe();
        var first = calc.calculate(base, List.of(opt));
        var second = calc.calculate(base, List.of(opt));
        assertEquals(first, second);
    }

    @Test
    void emptySelectionReturnsBaseCopyUnmutated() throws Exception {
        var calc = calcWith();
        Map<UUID, BigDecimal> base = baseRecipe();
        Map<UUID, BigDecimal> out = calc.calculate(base, List.of());
        assertEquals(base, out);
        assertNotSame(base, out);
    }

    @Test
    void returnedMapIsUnmodifiable() throws Exception {
        var calc = calcWith();
        Map<UUID, BigDecimal> out = calc.calculate(baseRecipe(), List.of());
        assertThrows(UnsupportedOperationException.class, () -> out.put(UUID.randomUUID(), BigDecimal.ONE));
    }

    @Test
    void nullBaseAndNullSelectionYieldEmptyResult() {
        var calc = calcWith();
        Map<UUID, BigDecimal> out = calc.calculate(null, null);
        assertTrue(out.isEmpty());
    }

    @Test
    void duplicateSelectedOptionIdsAreAppliedOnceInFirstOccurrenceOrder() throws Exception {
        UUID id = UUID.randomUUID();
        var add = option(id, InventoryEffectType.ADD, COFFEE_BEANS, new BigDecimal("9"), null, null);
        var calc = calcWith(add);
        // Selecting the same option twice must not double-count.
        Map<UUID, BigDecimal> out = calc.calculate(baseRecipe(), List.of(id, id, id));
        assertEquals(0, new BigDecimal("27").compareTo(out.get(COFFEE_BEANS)));
    }

    @Test
    void twoAddsOnTheSameIngredientCompose() throws Exception {
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        var optA = option(a, InventoryEffectType.ADD, COFFEE_BEANS, new BigDecimal("9"), null, null);
        var optB = option(b, InventoryEffectType.ADD, COFFEE_BEANS, new BigDecimal("18"), null, null);
        var calc = calcWith(optA, optB);
        // 18 base + 9 + 18 = 45
        assertEquals(0, new BigDecimal("45").compareTo(
                calc.calculate(baseRecipe(), List.of(a, b)).get(COFFEE_BEANS)));
    }

    @Test
    void addThenMultiplyComposesMultiplicatively() throws Exception {
        UUID add = UUID.randomUUID();
        UUID mul = UUID.randomUUID();
        var a = option(add, InventoryEffectType.ADD, SUGAR_SYRUP, new BigDecimal("10"), null, null);
        var m = option(mul, InventoryEffectType.MULTIPLY, SUGAR_SYRUP, null, new BigDecimal("0.50"), null);
        var calc = calcWith(a, m);
        // Order matters (documented): ADD first (20 + 10 = 30), then MULTIPLY
        // scales the current value (30 * 0.50 = 15.0000).
        assertEquals(0, new BigDecimal("15").compareTo(
                calc.calculate(baseRecipe(), List.of(add, mul)).get(SUGAR_SYRUP)));
    }

    @Test
    void multiplyThenAddAppliesInSelectionOrder() throws Exception {
        UUID add = UUID.randomUUID();
        UUID mul = UUID.randomUUID();
        var a = option(add, InventoryEffectType.ADD, SUGAR_SYRUP, new BigDecimal("10"), null, null);
        var m = option(mul, InventoryEffectType.MULTIPLY, SUGAR_SYRUP, null, new BigDecimal("0.50"), null);
        var calc = calcWith(a, m);
        // Reverse order: MULTIPLY first (20 * 0.50 = 10.0000), then ADD (10 + 10 = 20).
        assertEquals(0, new BigDecimal("20").compareTo(
                calc.calculate(baseRecipe(), List.of(mul, add)).get(SUGAR_SYRUP)));
    }

    @Test
    void removeMoreThanAvailableFloorsAtZero() throws Exception {
        UUID rid = UUID.randomUUID();
        var r = option(rid, InventoryEffectType.REMOVE, SUGAR_SYRUP, null, new BigDecimal("999"), null);
        var calc = calcWith(r);
        assertEquals(0, BigDecimal.ZERO.compareTo(calc.calculate(baseRecipe(), List.of(rid)).get(SUGAR_SYRUP)));
    }

    @Test
    void removeWithNullQuantityClearsTheFullCalculatedSourceAmount() throws Exception {
        UUID rid = UUID.randomUUID();
        // REMOVE with no explicit quantity → drop the entire source amount.
        var r = option(rid, InventoryEffectType.REMOVE, null, SUGAR_SYRUP, null, null, null);
        var calc = calcWith(r);
        assertEquals(0, BigDecimal.ZERO.compareTo(calc.calculate(baseRecipe(), List.of(rid)).get(SUGAR_SYRUP)));
    }

    @Test
    void replaceWhenSourceAbsentStillAddsTargetAtExplicitQuantity() throws Exception {
        UUID rid = UUID.randomUUID();
        // Base recipe contains no OAT_MILK row. REPLACE OAT_MILK -> FRESH_MILK
        // with an explicit 60 ml must clear the (zero) OAT cell and merge 60
        // ml into FRESH, which already had 150 ml from the base => 210 total.
        var r = option(rid, InventoryEffectType.REPLACE, OAT_MILK, FRESH_MILK,
                new BigDecimal("60"), null, null);
        var calc = calcWith(r);
        Map<UUID, BigDecimal> out = calc.calculate(baseRecipe(), List.of(rid));
        assertEquals(0, new BigDecimal("210").compareTo(out.get(FRESH_MILK)));
        assertEquals(0, BigDecimal.ZERO.compareTo(out.get(OAT_MILK)));
    }

    @Test
    void unknownOptionIdsAreIgnoredWithoutThrowing() throws Exception {
        var calc = calcWith();
        // Repo returns empty (mock default). Calculation must return base unchanged.
        Map<UUID, BigDecimal> out = calc.calculate(baseRecipe(), List.of(UUID.randomUUID()));
        assertEquals(baseRecipe(), out);
    }

    @Test
    void multiplySupportsFractionalPercentsWithScaleAndRounding() throws Exception {
        UUID m15 = UUID.randomUUID();
        UUID m10 = UUID.randomUUID();
        var fifteen = option(m15, InventoryEffectType.MULTIPLY, SUGAR_SYRUP, null, new BigDecimal("0.15"), null);
        var ten = option(m10, InventoryEffectType.MULTIPLY, SUGAR_SYRUP, null, new BigDecimal("0.10"), null);
        var calc = calcWith(ten, fifteen);
        // 20 * 0.10 = 2.0000
        assertEquals(0, new BigDecimal("2").compareTo(
                calc.calculate(baseRecipe(), List.of(m10)).get(SUGAR_SYRUP)));
        // 20 * 0.15 = 3.0000
        assertEquals(0, new BigDecimal("3").compareTo(
                calc.calculate(baseRecipe(), List.of(m15)).get(SUGAR_SYRUP)));
    }
}
