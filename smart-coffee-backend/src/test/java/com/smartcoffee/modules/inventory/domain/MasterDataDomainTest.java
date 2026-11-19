package com.smartcoffee.modules.inventory.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure-domain validations for the M2 master-data aggregates: ingredient and
 * unit-of-measure create/update guards (required fields, cost sign, decimal
 * scale / conversion factor bounds, positive shelf life). No Spring/DB.
 */
class MasterDataDomainTest {

    private static final UUID UNIT = UUID.randomUUID();

    @Test
    void ingredientCreationGuards() {
        assertThrows(IllegalArgumentException.class,
                () -> Ingredient.create(" ", "Name", UNIT, BigDecimal.ONE, true, false, null, true), "code required");
        assertThrows(IllegalArgumentException.class,
                () -> Ingredient.create("C", "Name", null, BigDecimal.ONE, true, false, null, true), "unit required");
        assertThrows(IllegalArgumentException.class,
                () -> Ingredient.create("C", "Name", UNIT, new BigDecimal("-1"), true, false, null, true),
                "cost >= 0");
        assertThrows(IllegalArgumentException.class,
                () -> Ingredient.create("C", "Name", UNIT, BigDecimal.ONE, true, true, 0, true),
                "shelf life must be positive when set");

        var i = Ingredient.create("C", "Name", UNIT, new BigDecimal("2.50"), true, true, 30, true);
        assertEquals(new BigDecimal("2.50"), i.standardCost());
        assertTrue(i.trackBatches());
        assertEquals(30, i.shelfLifeDays());

        i.updateDetails("C2", "Renamed", UNIT, BigDecimal.ONE, false, false, null, false);
        assertEquals("C2", i.code());
        assertFalse(i.trackBatches());
        assertNull(i.shelfLifeDays());
        assertFalse(i.active());
    }

    @Test
    void unitCreationGuards() {
        assertThrows(IllegalArgumentException.class,
                () -> UnitOfMeasure.create(" ", "Name", UnitType.MASS, 0, BigDecimal.ONE, true, true), "code required");
        assertThrows(IllegalArgumentException.class,
                () -> UnitOfMeasure.create("K", "Kg", UnitType.MASS, 7, BigDecimal.ONE, true, true),
                "scale must be 0..6");
        assertThrows(IllegalArgumentException.class,
                () -> UnitOfMeasure.create("K", "Kg", UnitType.MASS, 3, BigDecimal.ZERO, true, true),
                "factor must be positive");

        var u = UnitOfMeasure.create("KG", "Kilogram", UnitType.MASS, 3, BigDecimal.ONE, true, true);
        assertEquals(UnitType.MASS, u.type());
        assertTrue(u.baseUnit());
        u.update("KG", "Kilograms", UnitType.MASS, 2, new BigDecimal("1"), false, false);
        assertEquals("Kilograms", u.name());
        assertEquals(2, u.decimalScale());
        assertFalse(u.active());
    }
}
