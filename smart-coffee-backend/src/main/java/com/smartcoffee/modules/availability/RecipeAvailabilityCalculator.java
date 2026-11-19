package com.smartcoffee.modules.availability;

import com.smartcoffee.modules.availability.InventoryAvailabilityReader.StockSnapshot;
import com.smartcoffee.modules.availability.ServingMath.Servings;
import com.smartcoffee.modules.recipe.application.RecipeLineNormalizer;
import com.smartcoffee.modules.recipe.application.RecipeLineNormalizer.NormalizedLine;
import com.smartcoffee.modules.recipe.domain.RecipeVersion;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Normalizes a resolved recipe's base (DEFAULT / no-customer-selection) demand and
 * computes the achievable serving count for it (Slice 3A, spec §7/§15). This is the
 * "lower-level normalized Recipe demand" path the spec directs us to reuse — it calls
 * the shared {@link RecipeLineNormalizer} rather than fabricating a customer
 * configuration through {@code ConsumptionPlanService} for every catalog read.
 *
 * <p>It applies NO option effects (that is {@link OptionAvailabilityEvaluator}) and
 * writes nothing. Required items are the recipe's NON-optional lines (cups, lids,
 * packaging and other tracked non-optional lines are therefore included); optional
 * lines are reported separately and never constrain availability.
 */
@Service
public class RecipeAvailabilityCalculator {

    private final RecipeLineNormalizer normalizer;

    public RecipeAvailabilityCalculator(RecipeLineNormalizer normalizer) {
        this.normalizer = normalizer;
    }

    /**
     * Base per-single-sellable-unit demand, split into the REQUIRED (non-optional) and
     * OPTIONAL components, each keyed in ascending ingredient order. Both maps are in
     * each ingredient's base inventory unit.
     */
    public record BaseDemand(Map<UUID, BigDecimal> requiredPerServing, Map<UUID, BigDecimal> optionalPerServing) {

        /** Union of every ingredient the base recipe references (for batch stock load). */
        public java.util.Set<UUID> ingredients() {
            java.util.TreeSet<UUID> all = new java.util.TreeSet<>();
            all.addAll(requiredPerServing.keySet());
            all.addAll(optionalPerServing.keySet());
            return all;
        }
    }

    /**
     * Normalizes the resolved recipe into base demand. The caller must only invoke this
     * for a PRESENT recipe version; the no-recipe / stock-mode policy lives in
     * {@link AvailabilityService}.
     */
    public BaseDemand baseDemand(RecipeVersion version) {
        List<NormalizedLine> lines = normalizer.normalize(version);
        Map<UUID, BigDecimal> required = new TreeMap<>();
        Map<UUID, BigDecimal> optional = new TreeMap<>();
        for (NormalizedLine line : lines) {
            Map<UUID, BigDecimal> target = line.optional() ? optional : required;
            target.merge(line.ingredientId(), line.perUnit(), (left, right) -> left.add(right));
        }
        return new BaseDemand(required, optional);
    }

    /** Achievable servings for a required per-serving demand map against the snapshots. */
    public Servings servings(Map<UUID, BigDecimal> requiredPerServing, Map<UUID, StockSnapshot> snapshots) {
        return ServingMath.compute(requiredPerServing, snapshots);
    }
}
