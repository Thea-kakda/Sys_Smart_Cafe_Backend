package com.smartcoffee.modules.availability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartcoffee.modules.availability.InventoryAvailabilityReader.StockSnapshot;
import com.smartcoffee.modules.availability.RecipeAvailabilityCalculator.BaseDemand;
import com.smartcoffee.modules.recipe.application.RecipeLineNormalizer;
import com.smartcoffee.modules.recipe.application.RecipeLineNormalizer.NormalizedLine;
import com.smartcoffee.modules.recipe.domain.RecipeVersion;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RecipeAvailabilityCalculator} — the base (default) recipe
 * demand path (Slice 3A, spec §7/§22). It proves the calculator REUSES the shared
 * {@link RecipeLineNormalizer} (yield / wastage / unit conversion already folded into
 * per-unit base demand), splits required vs optional lines, merges duplicate
 * ingredients, and never lets optional lines constrain availability.
 */
class RecipeAvailabilityCalculatorTest {

    private static final UUID COFFEE = UUID.fromString("20000000-0000-0000-0000-000000000001");
    private static final UUID MILK = UUID.fromString("20000000-0000-0000-0000-000000000002");
    private static final UUID CUP = UUID.fromString("20000000-0000-0000-0000-000000000003");
    private static final UUID EXTRA_SHOT = UUID.fromString("20000000-0000-0000-0000-000000000004");

    private RecipeLineNormalizer normalizer;
    private RecipeAvailabilityCalculator calculator;

    @BeforeEach
    void setup() {
        normalizer = mock(RecipeLineNormalizer.class);
        calculator = new RecipeAvailabilityCalculator(normalizer);
    }

    @Test
    void requiredAndOptionalLinesAreSeparatedAndDuplicatesMerged() {
        RecipeVersion version = mock(RecipeVersion.class);
        when(normalizer.normalize(version)).thenReturn(List.of(
                new NormalizedLine(COFFEE, new BigDecimal("0.018"), 1, false),
                new NormalizedLine(COFFEE, new BigDecimal("0.002"), 2, false), // same ingredient
                new NormalizedLine(CUP, BigDecimal.ONE, 3, false),
                new NormalizedLine(EXTRA_SHOT, new BigDecimal("0.018"), 4, true))); // optional

        BaseDemand base = calculator.baseDemand(version);

        assertEquals(0, new BigDecimal("0.020").compareTo(base.requiredPerServing().get(COFFEE)),
                "duplicate required lines merge additively");
        assertEquals(0, BigDecimal.ONE.compareTo(base.requiredPerServing().get(CUP)));
        assertEquals(0, new BigDecimal("0.018").compareTo(base.optionalPerServing().get(EXTRA_SHOT)),
                "optional lines are reported separately");
        assertTrue(!base.requiredPerServing().containsKey(EXTRA_SHOT), "optional never in required");
    }

    @Test
    void ingredientsUnionIsDeterministicallyOrdered() {
        RecipeVersion version = mock(RecipeVersion.class);
        when(normalizer.normalize(version)).thenReturn(List.of(
                new NormalizedLine(MILK, BigDecimal.ONE, 1, false),
                new NormalizedLine(COFFEE, BigDecimal.ONE, 2, false),
                new NormalizedLine(EXTRA_SHOT, BigDecimal.ONE, 3, true)));
        BaseDemand base = calculator.baseDemand(version);
        List<UUID> ordered = List.copyOf(base.ingredients());
        assertEquals(COFFEE, ordered.get(0));
        assertEquals(MILK, ordered.get(1));
        assertEquals(EXTRA_SHOT, ordered.get(2));
    }

    @Test
    void servingsUsesTheSharedServingFormula() {
        Map<UUID, BigDecimal> required = new TreeMap<>(Map.of(COFFEE, BigDecimal.ONE));
        Map<UUID, StockSnapshot> stock = Map.of(COFFEE,
                new StockSnapshot(COFFEE, new BigDecimal("10"), BigDecimal.ZERO, new BigDecimal("10"), 1L));
        assertEquals(10, calculator.servings(required, stock).servings());
    }

    @Test
    void normalizerIsInvokedExactlyOnceForBaseDemand() {
        RecipeVersion version = mock(RecipeVersion.class);
        when(normalizer.normalize(any())).thenReturn(List.of());
        calculator.baseDemand(version);
        org.mockito.Mockito.verify(normalizer).normalize(version);
    }
}
