package com.smartcoffee.modules.availability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.smartcoffee.modules.availability.InventoryAvailabilityReader.StockSnapshot;
import com.smartcoffee.modules.availability.OptionAvailabilityEvaluator.Evaluation;
import com.smartcoffee.modules.availability.OptionAvailabilityEvaluator.GroupInput;
import com.smartcoffee.modules.availability.OptionAvailabilityEvaluator.OptionInput;
import com.smartcoffee.modules.availability.OptionGroupAvailabilityResult;
import com.smartcoffee.modules.catalog.domain.InventoryEffectType;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OptionAvailabilityEvaluator} (Slice 3A, spec §10/§11/§22
 * "Options" + "Complexity"). Proves ADD/REMOVE/REPLACE/MULTIPLY semantics match the
 * authoritative calculator, required-group satisfiability (Oat rescues a sold-out
 * Fresh), sugar-percentage multipliers, and that independent required groups never
 * trigger a Cartesian blow-up while an interacting component does respect the cap.
 */
class OptionAvailabilityEvaluatorTest {

    private final OptionAvailabilityEvaluator evaluator = new OptionAvailabilityEvaluator();

    private static final UUID CUP = id("01");
    private static final UUID SHOT = id("02");
    private static final UUID FRESH = id("03");
    private static final UUID OAT = id("04");
    private static final UUID SYRUP = id("05");

    private static UUID id(String tail) {
        return UUID.fromString("50000000-0000-0000-0000-" + tail + "000000000000".substring(tail.length()));
    }

    private static StockSnapshot snap(UUID ingredient, String available) {
        BigDecimal a = new BigDecimal(available);
        return new StockSnapshot(ingredient, a, BigDecimal.ZERO, a, 1L);
    }

    private static Map<UUID, StockSnapshot> stock(StockSnapshot... snaps) {
        Map<UUID, StockSnapshot> m = new TreeMap<>();
        for (StockSnapshot s : snaps) {
            m.put(s.ingredientId(), s);
        }
        return m;
    }

    private static OptionInput opt(UUID oid, InventoryEffectType type, UUID src, UUID tgt, String qty, String mult) {
        return new OptionInput(oid, "opt-" + oid.toString().substring(0, 4), BigDecimal.ZERO, true, type, src, tgt,
                qty == null ? null : new BigDecimal(qty), mult == null ? null : new BigDecimal(mult), 0);
    }

    private static GroupInput group(UUID gid, int min, Integer max, OptionInput... opts) {
        return new GroupInput(gid, "group", "SINGLE", min, max, List.of(opts));
    }

    // ---------------------------------------------------------------- per-option effects

    @Test
    void addOptionOutOfStockIsUnavailableWhileBaseRemainsOrderable() {
        Map<UUID, BigDecimal> base = new TreeMap<>(Map.of(CUP, BigDecimal.ONE));
        Map<UUID, StockSnapshot> snapshots = stock(snap(CUP, "10"), snap(SHOT, "0"));
        GroupInput extras = group(id("10"), 0, null, opt(id("aa"), InventoryEffectType.ADD, null, SHOT, "1", null));

        Evaluation eval = evaluator.evaluate(base, snapshots, List.of(extras), 1, 100);

        OptionAvailabilityResult extra = eval.perGroup().get(0).values().get(0);
        assertFalse(extra.available());
        assertTrue(extra.selectable());
        assertEquals(AvailabilityReason.OPTION_TARGET_INGREDIENT_UNAVAILABLE.code(), extra.reasonCodes().get(0));
        assertTrue(eval.configurationInStock(), "an OPTIONAL unavailable add-on must not block the product");
    }

    @Test
    void removeEffectFloorsAtZeroAndMirrorsCalculator() {
        Map<UUID, BigDecimal> working = new HashMap<>();
        working.put(FRESH, new BigDecimal("0.15"));
        OptionInput o = opt(id("b1"), InventoryEffectType.REMOVE, FRESH, null, null, null);
        Map<UUID, BigDecimal> out = evaluator.applyEffect(working, o);
        assertEquals(0, BigDecimal.ZERO.compareTo(out.get(FRESH)), "REMOVE with null qty zeroes the source");
    }

    @Test
    void replaceEffectMovesFullAmountToTarget() {
        Map<UUID, BigDecimal> working = new HashMap<>();
        working.put(FRESH, new BigDecimal("0.15"));
        OptionInput o = opt(id("b2"), InventoryEffectType.REPLACE, FRESH, OAT, null, null);
        Map<UUID, BigDecimal> out = evaluator.applyEffect(working, o);
        assertEquals(0, BigDecimal.ZERO.compareTo(out.get(FRESH)));
        assertEquals(0, new BigDecimal("0.15").compareTo(out.get(OAT)), "Fresh 150ml -> Oat 150ml");
    }

    @Test
    void multiplyEffectScalesSourceAtScaleFour() {
        Map<UUID, BigDecimal> working = new HashMap<>();
        working.put(SYRUP, new BigDecimal("0.01"));
        OptionInput o = opt(id("b3"), InventoryEffectType.MULTIPLY, SYRUP, null, null, "1.5");
        Map<UUID, BigDecimal> out = evaluator.applyEffect(working, o);
        assertEquals(0, new BigDecimal("0.0150").compareTo(out.get(SYRUP)));
    }

    @Test
    void legacyRemoveFallsBackToTargetIngredientLikeCalculator() {
        // A pre-V041 REMOVE row stores the affected ingredient in targetIngredientId.
        Map<UUID, BigDecimal> working = new HashMap<>();
        working.put(FRESH, new BigDecimal("0.2"));
        OptionInput legacy = opt(id("b4"), InventoryEffectType.REMOVE, null, FRESH, null, null);
        Map<UUID, BigDecimal> out = evaluator.applyEffect(working, legacy);
        assertEquals(0, BigDecimal.ZERO.compareTo(out.get(FRESH)));
    }

    // ---------------------------------------------------------------- required groups

    @Test
    void requiredMilkGroupSatisfiableByOatWhenFreshSoldOut() {
        Map<UUID, BigDecimal> base = new TreeMap<>(Map.of(CUP, BigDecimal.ONE));
        Map<UUID, StockSnapshot> snapshots = stock(snap(CUP, "10"), snap(FRESH, "0"), snap(OAT, "20"));
        GroupInput milk = group(id("20"), 1, 1,
                opt(id("c1"), InventoryEffectType.ADD, null, FRESH, "0.2", null),
                opt(id("c2"), InventoryEffectType.ADD, null, OAT, "0.2", null));

        Evaluation eval = evaluator.evaluate(base, snapshots, List.of(milk), 1, 100);
        OptionGroupAvailabilityResult g = eval.perGroup().get(0);
        assertTrue(g.required());
        assertFalse(g.values().get(0).available(), "Fresh unavailable");
        assertTrue(g.values().get(1).available(), "Oat available");
        assertTrue(g.atLeastOneValidSelection());
        assertTrue(eval.configurationInStock(), "the product remains orderable via Oat");
    }

    @Test
    void requiredGroupWithAllValuesUnavailableMakesConfigurationUnorderable() {
        Map<UUID, BigDecimal> base = new TreeMap<>();
        Map<UUID, StockSnapshot> snapshots = stock(snap(FRESH, "0"), snap(OAT, "0"));
        GroupInput milk = group(id("21"), 1, 1,
                opt(id("d1"), InventoryEffectType.ADD, null, FRESH, "0.2", null),
                opt(id("d2"), InventoryEffectType.ADD, null, OAT, "0.2", null));
        Evaluation eval = evaluator.evaluate(base, snapshots, List.of(milk), 1, 100);
        assertFalse(eval.perGroup().get(0).atLeastOneValidSelection());
        assertFalse(eval.configurationInStock());
    }

    @Test
    void sugarPercentagesUseCorrectMultipliersAndKeepProductOrderable() {
        Map<UUID, BigDecimal> base = new TreeMap<>(Map.of(SYRUP, new BigDecimal("0.01")));
        Map<UUID, StockSnapshot> snapshots = stock(snap(SYRUP, "0.006")); // enough for 50% only
        GroupInput sugar = group(id("22"), 1, 1,
                opt(id("e0"), InventoryEffectType.MULTIPLY, SYRUP, null, null, "0"),
                opt(id("e1"), InventoryEffectType.MULTIPLY, SYRUP, null, null, "0.5"),
                opt(id("e2"), InventoryEffectType.MULTIPLY, SYRUP, null, null, "1.0"),
                opt(id("e3"), InventoryEffectType.MULTIPLY, SYRUP, null, null, "1.5"));
        Evaluation eval = evaluator.evaluate(base, snapshots, List.of(sugar), 1, 100);
        List<OptionAvailabilityResult> v = eval.perGroup().get(0).values();
        assertTrue(v.get(0).available(), "0% syrup available even with no syrup stock");
        assertTrue(v.get(1).available(), "50% fits remaining syrup");
        assertFalse(v.get(2).available(), "100% exceeds remaining syrup");
        assertFalse(v.get(3).available(), "150% exceeds remaining syrup");
        assertTrue(eval.configurationInStock(), "orderable via an available sugar level");
    }

    // ---------------------------------------------------------------- complexity bound

    @Test
    void independentRequiredGroupsDoNotTriggerCartesianEnumeration() {
        // Milk (Oat, in stock) and Sugar (0%, in stock) touch disjoint ingredients →
        // two single-group components; even a cap of 1 must not trip them.
        Map<UUID, BigDecimal> base = new TreeMap<>();
        Map<UUID, StockSnapshot> snapshots = stock(snap(OAT, "5"), snap(SYRUP, "5"));
        GroupInput milk = group(id("30"), 1, 1, opt(id("f1"), InventoryEffectType.ADD, null, OAT, "0.2", null));
        GroupInput sugar = group(id("31"), 1, 1, opt(id("f2"), InventoryEffectType.MULTIPLY, SYRUP, null, null, "0.5"));
        Evaluation eval = evaluator.evaluate(base, snapshots, List.of(milk, sugar), 1, 1);
        assertFalse(eval.complexityExceeded());
        assertTrue(eval.configurationInStock());
    }

    @Test
    void interactingRequiredGroupsExceedingCapReturnConservativeVerdict() {
        // Two required groups whose options both ADD the SAME shared ingredient form one
        // interacting component; 3 alternatives x 3 = 9 combos must exceed a cap of 4.
        UUID shared = id("60");
        Map<UUID, BigDecimal> base = new TreeMap<>();
        Map<UUID, StockSnapshot> snapshots = stock(snap(shared, "100"));
        GroupInput g1 = group(id("40"), 1, 1,
                opt(id("a1"), InventoryEffectType.ADD, null, shared, "1", null),
                opt(id("a2"), InventoryEffectType.ADD, null, shared, "1", null),
                opt(id("a3"), InventoryEffectType.ADD, null, shared, "1", null));
        GroupInput g2 = group(id("41"), 1, 1,
                opt(id("b1"), InventoryEffectType.ADD, null, shared, "1", null),
                opt(id("b2"), InventoryEffectType.ADD, null, shared, "1", null),
                opt(id("b3"), InventoryEffectType.ADD, null, shared, "1", null));
        Evaluation eval = evaluator.evaluate(base, snapshots, List.of(g1, g2), 1, 4);
        assertTrue(eval.complexityExceeded());
        assertFalse(eval.configurationInStock(), "an undecided interacting component is conservative");
    }

    @Test
    void optionRequestOrderingDoesNotChangeVerdict() {
        Map<UUID, BigDecimal> base = new TreeMap<>(Map.of(CUP, BigDecimal.ONE));
        Map<UUID, StockSnapshot> snapshots = stock(snap(CUP, "10"), snap(FRESH, "0"), snap(OAT, "20"));
        OptionInput fresh = opt(id("c1"), InventoryEffectType.ADD, null, FRESH, "0.2", null);
        OptionInput oat = opt(id("c2"), InventoryEffectType.ADD, null, OAT, "0.2", null);
        boolean a = evaluator.evaluate(base, snapshots, List.of(group(id("50"), 1, 1, fresh, oat)), 1, 100)
                .configurationInStock();
        boolean b = evaluator.evaluate(base, snapshots, List.of(group(id("50"), 1, 1, oat, fresh)), 1, 100)
                .configurationInStock();
        assertEquals(a, b);
        assertTrue(a);
    }
}
