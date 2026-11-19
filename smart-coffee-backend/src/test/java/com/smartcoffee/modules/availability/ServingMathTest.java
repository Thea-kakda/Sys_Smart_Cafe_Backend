package com.smartcoffee.modules.availability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartcoffee.modules.availability.InventoryAvailabilityReader.StockSnapshot;
import com.smartcoffee.modules.availability.ServingMath.Servings;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ServingMath} — the single base-serving formula of the
 * availability engine (Slice 3A, spec §7 / §22 "Base servings"). Covers the single
 * and multiple required-ingredient cases, the bottleneck, zero / reserved / missing
 * / inconsistent balances, decimal + large-number behaviour, the no-constraint
 * ("unlimited") path and the input-immutability guarantee.
 */
class ServingMathTest {

    private static final UUID A = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID B = UUID.fromString("10000000-0000-0000-0000-000000000002");

    private static StockSnapshot snap(UUID id, String current, String reserved) {
        BigDecimal c = new BigDecimal(current);
        BigDecimal r = new BigDecimal(reserved);
        return new StockSnapshot(id, c, r, c.subtract(r), 1L);
    }

    @Test
    void singleRequiredIngredientDividesAvailable() {
        Map<UUID, BigDecimal> need = new TreeMap<>(Map.of(A, new BigDecimal("0.5")));
        Map<UUID, StockSnapshot> stock = Map.of(A, snap(A, "3", "0"));
        Servings s = ServingMath.compute(need, stock);
        assertTrue(s.limited());
        assertEquals(6, s.servings());
        assertEquals(A, s.bottleneck());
        assertFalse(s.anomaly());
    }

    @Test
    void bottleneckIsTheMinimumAcrossMultipleIngredients() {
        Map<UUID, BigDecimal> need = new TreeMap<>();
        need.put(A, BigDecimal.ONE); // 10 / 1 = 10
        need.put(B, new BigDecimal("2")); // 5 / 2 = 2 (floor) -> bottleneck
        Map<UUID, StockSnapshot> stock = Map.of(A, snap(A, "10", "0"), B, snap(B, "5", "0"));
        Servings s = ServingMath.compute(need, stock);
        assertEquals(2, s.servings());
        assertEquals(B, s.bottleneck());
    }

    @Test
    void reservedStockReducesAvailability() {
        Map<UUID, BigDecimal> need = new TreeMap<>(Map.of(A, BigDecimal.ONE));
        Map<UUID, StockSnapshot> stock = Map.of(A, snap(A, "10", "3"));
        assertEquals(7, ServingMath.compute(need, stock).servings());
    }

    @Test
    void zeroAvailableYieldsZeroServings() {
        Map<UUID, BigDecimal> need = new TreeMap<>(Map.of(A, BigDecimal.ONE));
        Map<UUID, StockSnapshot> stock = Map.of(A, snap(A, "0", "0"));
        Servings s = ServingMath.compute(need, stock);
        assertTrue(s.limited());
        assertEquals(0, s.servings());
        assertFalse(s.anomaly());
    }

    @Test
    void missingBalanceRowIsTreatedAsZeroNotFabricated() {
        Map<UUID, BigDecimal> need = new TreeMap<>(Map.of(A, BigDecimal.ONE));
        Servings s = ServingMath.compute(need, Map.of());
        assertTrue(s.limited());
        assertEquals(0, s.servings());
        assertFalse(s.anomaly(), "an absent row is zero-available, not a data anomaly");
    }

    @Test
    void negativeAvailabilityIsAnAnomalyAndReportsNoBottleneck() {
        Map<UUID, BigDecimal> need = new TreeMap<>(Map.of(A, BigDecimal.ONE));
        Map<UUID, StockSnapshot> stock = Map.of(A, snap(A, "2", "5")); // reserved > current
        Servings s = ServingMath.compute(need, stock);
        assertTrue(s.anomaly());
        assertNull(s.bottleneck(), "an anomaly exposes no bottleneck");
        assertFalse(ServingMath.satisfies(s, 1), "an anomaly is conservatively never satisfiable");
    }

    @Test
    void nonPositiveRequirementDoesNotConstrain() {
        Map<UUID, BigDecimal> need = new LinkedHashMap<>();
        need.put(A, BigDecimal.ZERO); // zero line ignored
        Servings s = ServingMath.compute(need, Map.of(A, snap(A, "0", "0")));
        assertFalse(s.limited(), "nothing constrains servings");
    }

    @Test
    void emptyRequirementIsUnlimited() {
        Servings s = ServingMath.compute(new TreeMap<>(), Map.of());
        assertFalse(s.limited());
        assertNull(s.bottleneck());
        assertTrue(ServingMath.satisfies(s, 5_000), "unlimited is always satisfiable");
    }

    @Test
    void largeAvailableCountIsClampedToMaxServings() {
        Map<UUID, BigDecimal> need = new TreeMap<>(Map.of(A, new BigDecimal("0.0001")));
        Map<UUID, StockSnapshot> stock = Map.of(A, snap(A, "999999999", "0"));
        Servings s = ServingMath.compute(need, stock);
        assertEquals(ServingMath.MAX_SERVINGS, s.servings());
    }

    @Test
    void computeDoesNotMutateInputs() {
        Map<UUID, BigDecimal> need = new TreeMap<>();
        need.put(A, BigDecimal.ONE);
        need.put(B, BigDecimal.ONE);
        Map<UUID, StockSnapshot> stock = new LinkedHashMap<>(Map.of(A, snap(A, "10", "0"), B, snap(B, "10", "0")));
        Map<UUID, BigDecimal> needCopy = new TreeMap<>(need);
        ServingMath.compute(need, stock);
        assertEquals(needCopy, need, "the requirement map is never mutated");
        assertEquals(2, stock.size(), "the snapshot map is never mutated");
    }

    @Test
    void satisfiesReflectsAnomalyUnlimitedAndCount() {
        assertFalse(ServingMath.satisfies(new Servings(true, 5, true, null), 1), "anomaly is never satisfiable");
        assertTrue(ServingMath.satisfies(Servings.unlimited(), 999));
        assertTrue(ServingMath.satisfies(new Servings(true, 5, false, A), 5));
        assertFalse(ServingMath.satisfies(new Servings(true, 5, false, A), 6));
    }
}
