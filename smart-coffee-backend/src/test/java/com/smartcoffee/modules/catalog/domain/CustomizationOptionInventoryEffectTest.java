package com.smartcoffee.modules.catalog.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Domain guards for the V039 option inventory-effect surface. These tests do
 * not exercise persistence (Slice 1 keeps option-effect persistence for the
 * integration test); they lock the shape rules the service mirrors so the DB
 * CHECK constraint ck_option_effect_shape can never be reached with a
 * half-populated row.
 */
class CustomizationOptionInventoryEffectTest {

    private static CustomizationOption newOption() throws Exception {
        var ctor = CustomizationOption.class.getDeclaredConstructor();
        ctor.setAccessible(true);
        CustomizationOption o = ctor.newInstance();
        set(o, "id", UUID.randomUUID());
        set(o, "groupId", UUID.randomUUID());
        set(o, "code", "opt");
        set(o, "name", "Option");
        set(o, "additionalPrice", BigDecimal.ZERO);
        set(o, "displayOrder", 0);
        set(o, "active", true);
        set(o, "inventoryEffectType", InventoryEffectType.NONE);
        return o;
    }

    private static void set(Object target, String field, Object value) throws Exception {
        Field f = target.getClass().getDeclaredField(field);
        f.setAccessible(true);
        f.set(target, value);
    }

    @Test
    void addRequiresTargetAndPositiveQuantity() throws Exception {
        var o = newOption();
        UUID ing = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () ->
                o.applyInventoryEffect(InventoryEffectType.ADD, null, null, new BigDecimal("1"), null, null));
        assertThrows(IllegalArgumentException.class, () ->
                o.applyInventoryEffect(InventoryEffectType.ADD, null, ing, BigDecimal.ZERO, null, null));
        o.applyInventoryEffect(InventoryEffectType.ADD, null, ing, new BigDecimal("18"), null, null);
        assertEquals(InventoryEffectType.ADD, o.inventoryEffectType());
        assertEquals(ing, o.targetIngredientId());
        assertEquals(0, new BigDecimal("18").compareTo(o.effectQuantity()));
        assertNull(o.effectMultiplier());
        assertNull(o.replacesOptionId());
    }

    @Test
    void removeRequiresTargetAndPositiveQuantity() throws Exception {
        var o = newOption();
        UUID ing = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () ->
                o.applyInventoryEffect(InventoryEffectType.REMOVE, ing, null, new BigDecimal("-1"), null, null));
        o.applyInventoryEffect(InventoryEffectType.REMOVE, ing, null, new BigDecimal("10"), null, null);
        assertEquals(InventoryEffectType.REMOVE, o.inventoryEffectType());
        assertEquals(new BigDecimal("10"), o.effectQuantity());
    }

    @Test
    void replaceRequiresSourceAndTargetAndRejectsSameIngredient() throws Exception {
        var o = newOption();
        UUID milk = UUID.randomUUID();
        UUID oat = UUID.randomUUID();
        // Missing target
        assertThrows(IllegalArgumentException.class, () ->
                o.applyInventoryEffect(InventoryEffectType.REPLACE, milk, null, null, null, null));
        // Missing source
        assertThrows(IllegalArgumentException.class, () ->
                o.applyInventoryEffect(InventoryEffectType.REPLACE, null, oat, null, null, null));
        // Same ingredient for both
        assertThrows(IllegalArgumentException.class, () ->
                o.applyInventoryEffect(InventoryEffectType.REPLACE, milk, milk, null, null, null));
        // Valid explicit swap
        o.applyInventoryEffect(InventoryEffectType.REPLACE, milk, oat, null, null, null);
        assertEquals(InventoryEffectType.REPLACE, o.inventoryEffectType());
        assertEquals(milk, o.sourceIngredientId());
        assertEquals(oat, o.targetIngredientId());
        assertNull(o.effectQuantity());
        assertNull(o.effectMultiplier());
    }

    @Test
    void removeAndMultiplyUseSourceColumnTargetMustBeNull() throws Exception {
        var o = newOption();
        UUID ice = UUID.randomUUID();
        // REMOVE must not populate target.
        assertThrows(IllegalArgumentException.class, () ->
                o.applyInventoryEffect(InventoryEffectType.REMOVE, ice, UUID.randomUUID(),
                        new BigDecimal("10"), null, null));
        o.applyInventoryEffect(InventoryEffectType.REMOVE, ice, null, new BigDecimal("10"), null, null);
        assertEquals(ice, o.sourceIngredientId());
        assertNull(o.targetIngredientId());
        // MULTIPLY must not populate target either.
        UUID syrup = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () ->
                o.applyInventoryEffect(InventoryEffectType.MULTIPLY, syrup, UUID.randomUUID(),
                        null, new BigDecimal("0.50"), null));
        o.applyInventoryEffect(InventoryEffectType.MULTIPLY, syrup, null, null, new BigDecimal("0.50"), null);
        assertEquals(syrup, o.sourceIngredientId());
        assertNull(o.targetIngredientId());
    }

    @Test
    void multiplyRequiresTargetAndNonNegativeMultiplier() throws Exception {
        var o = newOption();
        UUID sugarSyrup = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () ->
                o.applyInventoryEffect(InventoryEffectType.MULTIPLY, sugarSyrup, null, null, new BigDecimal("-0.01"), null));
        // 0.00 is allowed (no sugar).
        o.applyInventoryEffect(InventoryEffectType.MULTIPLY, sugarSyrup, null, null, new BigDecimal("0.00"), null);
        assertEquals(new BigDecimal("0.00"), o.effectMultiplier());
        // 1.50 is allowed (extra sugar).
        o.applyInventoryEffect(InventoryEffectType.MULTIPLY, sugarSyrup, null, null, new BigDecimal("1.50"), null);
        assertEquals(new BigDecimal("1.50"), o.effectMultiplier());
        assertNull(o.effectQuantity());
    }

    @Test
    void settingNoneClearsEveryAuxiliaryField() throws Exception {
        var o = newOption();
        UUID ing = UUID.randomUUID();
        o.applyInventoryEffect(InventoryEffectType.ADD, null, ing, new BigDecimal("18"), null, null);
        o.applyInventoryEffect(InventoryEffectType.NONE, null, null, null, null, null);
        assertEquals(InventoryEffectType.NONE, o.inventoryEffectType());
        assertNull(o.targetIngredientId());
        assertNull(o.effectQuantity());
        assertNull(o.effectMultiplier());
        assertNull(o.replacesOptionId());
    }
}
