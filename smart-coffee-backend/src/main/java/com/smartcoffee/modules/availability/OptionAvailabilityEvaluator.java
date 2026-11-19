package com.smartcoffee.modules.availability;

import com.smartcoffee.modules.availability.InventoryAvailabilityReader.StockSnapshot;
import com.smartcoffee.modules.availability.ServingMath.Servings;
import com.smartcoffee.modules.catalog.domain.InventoryEffectType;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Evaluates customization OPTION availability against a valid base configuration and
 * decides required-group satisfiability with a BOUNDED interaction search (Slice 3A,
 * spec §10/§11/§15).
 *
 * <p>Each allowed option is evaluated independently by applying its single inventory
 * effect (ADD / REMOVE / REPLACE / MULTIPLY) to a copy of the base required per-serving
 * demand and checking inventory coverage for the requested quantity. The per-effect
 * arithmetic mirrors the authoritative {@code InventoryEffectCalculator} /
 * {@code ConsumptionPlanService} semantics so availability never disagrees with the
 * demand that checkout would actually reserve.
 *
 * <p>Orderability across REQUIRED groups: groups whose options touch DISJOINT
 * ingredients are decided independently (no Cartesian blow-up — spec §11). Only when
 * two or more required groups interact on the same inventory item is a bounded
 * combination search performed, capped at {@code complexityCap}; exceeding it returns
 * a conservative not-orderable verdict tagged
 * {@link AvailabilityReason#AVAILABILITY_COMPLEXITY_LIMIT}. It never mutates state.
 */
@Service
public class OptionAvailabilityEvaluator {

    /** Immutable input describing one option for evaluation (loaded from the option row). */
    public record OptionInput(UUID optionId, String name, BigDecimal additionalPrice, boolean active,
            InventoryEffectType effectType, UUID sourceIngredientId, UUID targetIngredientId,
            BigDecimal effectQuantity, BigDecimal effectMultiplier, int displayOrder) {
    }

    /** Immutable input describing one option group allowed for the product. */
    public record GroupInput(UUID groupId, String name, String selectionType, int minimumSelections,
            Integer maximumSelections, List<OptionInput> options) {

        public boolean required() {
            return minimumSelections >= 1;
        }
    }

    /**
     * Aggregate verdict for one Variant's option space.
     *
     * @param perGroup            group/value availability results for the response.
     * @param configurationInStock true when at least one assignment of every required
     *                             group yields an inventory-satisfiable configuration
     *                             (independent of base/default serving estimate).
     * @param complexityExceeded   true when interacting required groups exceeded the
     *                             combination cap and could not be safely decided.
     */
    public record Evaluation(List<OptionGroupAvailabilityResult> perGroup, boolean configurationInStock,
            boolean complexityExceeded) {
    }

    public Evaluation evaluate(Map<UUID, BigDecimal> baseRequiredPerServing, Map<UUID, StockSnapshot> snapshots,
            List<GroupInput> groups, int quantity, int complexityCap) {

        List<OptionGroupAvailabilityResult> perGroup = new ArrayList<>();
        List<GroupInput> requiredGroups = new ArrayList<>();

        for (GroupInput g : groups) {
            List<OptionAvailabilityResult> values = new ArrayList<>();
            List<OptionInput> available = new ArrayList<>();
            for (OptionInput o : g.options()) {
                OptionAvailabilityResult r = evaluateOption(baseRequiredPerServing, snapshots, o, quantity);
                values.add(r);
                if (o.active() && r.available()) {
                    available.add(o);
                }
            }
            boolean atLeastOneValid = values.stream().anyMatch(v -> v.selectable() && v.available());
            perGroup.add(new OptionGroupAvailabilityResult(g.groupId(), g.name(), g.selectionType(), g.required(),
                    g.minimumSelections(), g.maximumSelections(), atLeastOneValid, values));
            if (g.required()) {
                requiredGroups.add(g);
            }
        }

        // Orderability requires EVERY required group to be jointly satisfiable.
        ConfigurationVerdict verdict = decideConfiguration(baseRequiredPerServing, snapshots, requiredGroups,
                quantity, complexityCap);

        return new Evaluation(perGroup, verdict.inStock(), verdict.complexityExceeded());
    }

    /** Evaluates ONE option: apply its effect to a copy of base demand, then check coverage. */
    private OptionAvailabilityResult evaluateOption(Map<UUID, BigDecimal> baseRequiredPerServing,
            Map<UUID, StockSnapshot> snapshots, OptionInput o, int quantity) {
        boolean selectable = o.active();
        Map<UUID, BigDecimal> applied = applyEffect(new HashMap<>(baseRequiredPerServing), o);
        Servings s = ServingMath.compute(new TreeMap<>(applied), snapshots);
        boolean available = ServingMath.satisfies(s, quantity);
        List<String> reasons = new ArrayList<>();
        if (!selectable) {
            reasons.add(AvailabilityReason.OPTION_INACTIVE.code());
        } else if (!available) {
            reasons.add(optionUnavailableReason(o, s, snapshots));
        }
        Integer availableQuantity = s.limited() ? s.servings() : null;
        return new OptionAvailabilityResult(o.optionId(), o.active(), selectable, available, availableQuantity,
                o.additionalPrice(), reasons);
    }

    /** Chooses the most specific reason for an unavailable option. */
    private String optionUnavailableReason(OptionInput o, Servings s, Map<UUID, StockSnapshot> snapshots) {
        if (s.anomaly()) {
            return AvailabilityReason.INVENTORY_DATA_INCONSISTENT.code();
        }
        UUID bottleneck = s.bottleneck();
        if (bottleneck == null) {
            return AvailabilityReason.OPTION_OUT_OF_STOCK.code();
        }
        if (bottleneck.equals(o.targetIngredientId())) {
            return AvailabilityReason.OPTION_TARGET_INGREDIENT_UNAVAILABLE.code();
        }
        if (bottleneck.equals(o.sourceIngredientId())) {
            return AvailabilityReason.OPTION_SOURCE_INGREDIENT_UNAVAILABLE.code();
        }
        return AvailabilityReason.OPTION_OUT_OF_STOCK.code();
    }

    /**
     * Applies one option's inventory effect to a mutable per-serving demand copy, in
     * base units. Mirrors the authoritative {@code InventoryEffectCalculator} exactly
     * (same source resolution, same {@code getOrDefault(ZERO)} semantics, scale 4
     * HALF_UP on MULTIPLY, REMOVE floored at zero) so availability NEVER disagrees with
     * the demand checkout would reserve.
     */
    Map<UUID, BigDecimal> applyEffect(Map<UUID, BigDecimal> working, OptionInput o) {
        InventoryEffectType t = o.effectType();
        if (t == null || t == InventoryEffectType.NONE) {
            return working;
        }
        UUID src = resolveSource(o);
        UUID tgt = o.targetIngredientId();
        BigDecimal qty = o.effectQuantity();
        BigDecimal mult = o.effectMultiplier();
        switch (t) {
            case ADD -> {
                if (tgt != null && qty != null && qty.signum() > 0) {
                    working.merge(tgt, qty, (left, right) -> left.add(right));
                }
            }
            case REMOVE -> {
                if (src != null) {
                    BigDecimal current = working.getOrDefault(src, BigDecimal.ZERO);
                    BigDecimal next = (qty == null) ? BigDecimal.ZERO : current.subtract(qty).max(BigDecimal.ZERO);
                    working.put(src, next);
                }
            }
            case REPLACE -> {
                if (src != null && tgt != null && !src.equals(tgt)) {
                    BigDecimal removed = working.getOrDefault(src, BigDecimal.ZERO);
                    BigDecimal add = (qty != null) ? qty : removed;
                    working.put(src, BigDecimal.ZERO);
                    if (add.signum() > 0) {
                        working.merge(tgt, add, (left, right) -> left.add(right));
                    }
                }
            }
            case MULTIPLY -> {
                if (src != null && mult != null) {
                    BigDecimal current = working.getOrDefault(src, BigDecimal.ZERO);
                    BigDecimal next = current.multiply(mult).setScale(4, RoundingMode.HALF_UP);
                    working.put(src, next);
                }
            }
            default -> { /* NONE */ }
        }
        return working;
    }

    /**
     * Mirrors {@code InventoryEffectCalculator.resolveSource} for the fields an
     * {@link OptionInput} carries. Post-V041 rows set {@code sourceIngredientId}
     * explicitly; a legacy REMOVE/MULTIPLY row stored the affected ingredient in
     * {@code targetIngredientId} instead, so we fall back to keep availability aligned
     * with the calculator. (The calculator's REPLACE-via-replacesOptionId legacy is not
     * reachable here because {@link OptionInput} does not model an option-to-option
     * reference; those pre-V041 rows have since been forward-migrated.)
     */
    private UUID resolveSource(OptionInput o) {
        if (o.sourceIngredientId() != null) {
            return o.sourceIngredientId();
        }
        InventoryEffectType t = o.effectType();
        if (t == InventoryEffectType.REMOVE || t == InventoryEffectType.MULTIPLY) {
            return o.targetIngredientId();
        }
        return null;
    }


    /** Ingredients an option can change (its effect endpoints). */
    private Set<UUID> affected(OptionInput o) {
        Set<UUID> s = new TreeSet<>();
        if (o.effectType() == null || o.effectType() == InventoryEffectType.NONE) {
            return s;
        }
        if (o.sourceIngredientId() != null) {
            s.add(o.sourceIngredientId());
        }
        if (o.targetIngredientId() != null) {
            s.add(o.targetIngredientId());
        }
        return s;
    }

    private record ConfigurationVerdict(boolean inStock, boolean complexityExceeded) {
    }

    /**
     * Decides whether a joint assignment of every required group is inventory-satisfiable.
     * Independent groups (disjoint affected ingredients) are solved separately; a maximal
     * interacting component uses a bounded enumeration up to {@code complexityCap}.
     */
    private ConfigurationVerdict decideConfiguration(Map<UUID, BigDecimal> baseRequiredPerServing,
            Map<UUID, StockSnapshot> snapshots, List<GroupInput> requiredGroups, int quantity, int complexityCap) {

        if (requiredGroups.isEmpty()) {
            // No required selection: the base/default configuration must itself be covered.
            Servings s = ServingMath.compute(new TreeMap<>(baseRequiredPerServing), snapshots);
            return new ConfigurationVerdict(ServingMath.satisfies(s, quantity), false);
        }

        // Partition required groups into interacting components (union-find on shared ingredients).
        Map<Integer, Integer> parent = new HashMap<>();
        for (int i = 0; i < requiredGroups.size(); i++) {
            parent.put(i, i);
        }
        Map<UUID, Integer> owner = new HashMap<>();
        for (int i = 0; i < requiredGroups.size(); i++) {
            for (OptionInput o : requiredGroups.get(i).options()) {
                for (UUID ing : affected(o)) {
                    Integer prev = owner.putIfAbsent(ing, i);
                    if (prev != null) {
                        union(parent, prev, i);
                    }
                }
            }
        }
        Map<Integer, List<Integer>> components = new LinkedHashMap<>();
        for (int i = 0; i < requiredGroups.size(); i++) {
            components.computeIfAbsent(find(parent, i), k -> new ArrayList<>()).add(i);
        }

        // A component is satisfiable if some combination of its groups' available options
        // keeps every item it governs covered. Components are disjoint, so all must pass.
        for (List<Integer> comp : components.values()) {
            Verdict v = solveComponent(baseRequiredPerServing, snapshots, requiredGroups, comp, quantity, complexityCap);
            if (v.exceeded) {
                return new ConfigurationVerdict(false, true);
            }
            if (!v.satisfiable) {
                return new ConfigurationVerdict(false, false);
            }
        }
        return new ConfigurationVerdict(true, false);
    }

    private static class Verdict {
        boolean satisfiable;
        boolean exceeded;

        Verdict(boolean satisfiable, boolean exceeded) {
            this.satisfiable = satisfiable;
            this.exceeded = exceeded;
        }
    }

    private Verdict solveComponent(Map<UUID, BigDecimal> base, Map<UUID, StockSnapshot> snapshots,
            List<GroupInput> requiredGroups, List<Integer> comp, int quantity, int complexityCap) {

        // Candidate available option(s) per group in this component.
        List<List<OptionInput>> perGroupChoices = new ArrayList<>();
        for (int gi : comp) {
            GroupInput g = requiredGroups.get(gi);
            List<OptionInput> choices = new ArrayList<>();
            for (OptionInput o : g.options()) {
                if (!o.active()) {
                    continue;
                }
                Map<UUID, BigDecimal> applied = applyEffect(new HashMap<>(base), o);
                if (ServingMath.satisfies(ServingMath.compute(new TreeMap<>(applied), snapshots), quantity)) {
                    choices.add(o);
                }
            }
            if (choices.isEmpty()) {
                return new Verdict(false, false); // this required group has no available value
            }
            perGroupChoices.add(choices);
        }

        // Single-group component (the overwhelmingly common case) → independent. A
        // required group's options are ALTERNATIVES (choose one), not a Cartesian
        // product, so its option count never trips the combination cap (spec §11).
        if (comp.size() == 1) {
            return new Verdict(true, false);
        }

        // Interacting multi-group component → the search space is the Cartesian product
        // of the groups' alternatives; bound it before enumerating.
        long combos = 1L;
        for (List<OptionInput> choices : perGroupChoices) {
            combos *= choices.size();
            if (combos > complexityCap) {
                return new Verdict(false, true);
            }
        }
        return enumerate(base, snapshots, perGroupChoices, 0, new HashMap<>(base), quantity);
    }

    private Verdict enumerate(Map<UUID, BigDecimal> base, Map<UUID, StockSnapshot> snapshots,
            List<List<OptionInput>> perGroupChoices, int idx, Map<UUID, BigDecimal> working, int quantity) {
        if (idx == perGroupChoices.size()) {
            boolean ok = ServingMath.satisfies(ServingMath.compute(new TreeMap<>(working), snapshots), quantity);
            return new Verdict(ok, false);
        }
        for (OptionInput o : perGroupChoices.get(idx)) {
            Map<UUID, BigDecimal> next = applyEffect(new HashMap<>(working), o);
            Verdict v = enumerate(base, snapshots, perGroupChoices, idx + 1, next, quantity);
            if (v.satisfiable) {
                return v;
            }
            if (v.exceeded) {
                return v;
            }
        }
        return new Verdict(false, false);
    }

    // ---- union-find helpers ----
    private static int find(Map<Integer, Integer> p, int x) {
        int r = x;
        while (p.get(r) != r) {
            r = p.get(r);
        }
        while (p.get(x) != r) {
            int n = p.get(x);
            p.put(x, r);
            x = n;
        }
        return r;
    }

    private static void union(Map<Integer, Integer> p, int a, int b) {
        int ra = find(p, a);
        int rb = find(p, b);
        if (ra != rb) {
            p.put(rb, ra);
        }
    }
}
