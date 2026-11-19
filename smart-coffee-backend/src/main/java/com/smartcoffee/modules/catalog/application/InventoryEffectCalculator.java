package com.smartcoffee.modules.catalog.application;

import com.smartcoffee.modules.catalog.domain.CustomizationOption;
import com.smartcoffee.modules.catalog.domain.InventoryEffectType;
import com.smartcoffee.modules.catalog.persistence.CustomizationOptionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Deterministic consumption calculator. Takes a base per-line ingredient
 * consumption map (ingredientId -&gt; quantity) plus the optionIds a customer
 * selected, and produces an effective consumption map after applying every
 * {@link InventoryEffectType} the options declare.
 *
 * <p>Slice 1A reshapes the option model (V041) to expose an explicit
 * source/target ingredient split. The calculator reads source/target directly
 * and no longer resolves the REPLACE source through an option-to-option
 * reference; a legacy path is retained for the transition window so a row
 * written under V039 (only targetIngredientId + replacesOptionId populated for
 * REMOVE/MULTIPLY/REPLACE) still produces the correct consumption.
 *
 * <p>Contract:
 * <ul>
 *   <li>Base map is never mutated; the returned map is unmodifiable and
 *       independent.</li>
 *   <li>Unknown option ids are silently ignored (historical orders may
 *       reference soft-deleted options; the cart line stays renderable).</li>
 *   <li>Duplicate selection of the same option id is idempotent: the effect
 *       is applied once, in first-occurrence order.</li>
 *   <li>ADD: result[target] += effectQuantity.</li>
 *   <li>REMOVE: when effectQuantity is null, result[source] := 0 (the whole
 *       calculated source amount is removed); otherwise
 *       result[source] -= effectQuantity, floored at zero.</li>
 *   <li>REPLACE: removes the source ingredient's calculated amount (the full
 *       value, not just an option-declared delta) and adds the target at
 *       either the caller-supplied effectQuantity or the removed source
 *       amount, preserving "Fresh Milk 150 ml -&gt; Oat Milk 150 ml".</li>
 *   <li>MULTIPLY: result[source] := result[source] * effectMultiplier, scale
 *       4 HALF_UP. Never touches any other ingredient.</li>
 *   <li>Effects apply in selection order; two MULTIPLYs on the same source
 *       compose multiplicatively; ADD then REMOVE on the same ingredient
 *       net-adds; REMOVE then ADD may leave zero. All deterministic.</li>
 * </ul>
 */
@Service
public class InventoryEffectCalculator {

    private static final int SCALE = 4;
    private static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    private final CustomizationOptionRepository options;

    public InventoryEffectCalculator(CustomizationOptionRepository options) {
        this.options = options;
    }

    // Map.merge(BigDecimal::add) operands are non-null by construction; JDT's
    // null analysis cannot prove it across an unannotated call site.
    @SuppressWarnings("null")
    public Map<UUID, BigDecimal> calculate(Map<UUID, BigDecimal> base, Collection<UUID> selectedOptionIds) {
        Map<UUID, BigDecimal> result = new LinkedHashMap<>();
        if (base != null) {
            base.forEach((k, v) -> result.put(k, v));
        }
        if (selectedOptionIds == null || selectedOptionIds.isEmpty()) {
            return Collections.unmodifiableMap(result);
        }
        // De-duplicate while preserving first-occurrence order so a repeated
        // selection is idempotent (matches "apply once" rule).
        java.util.LinkedHashSet<UUID> orderedUnique = new java.util.LinkedHashSet<>(selectedOptionIds);
        List<CustomizationOption> loaded = options.findAllById(orderedUnique);
        Map<UUID, CustomizationOption> byId = new LinkedHashMap<>();
        for (CustomizationOption o : loaded) {
            byId.put(o.id(), o);
        }
        for (UUID id : orderedUnique) {
            CustomizationOption o = byId.get(id);
            if (o == null) {
                continue;
            }
            InventoryEffectType t = o.inventoryEffectType();
            if (t == null || t == InventoryEffectType.NONE) {
                continue;
            }
            UUID src = resolveSource(o);
            UUID tgt = o.targetIngredientId();
            BigDecimal qty = o.effectQuantity();
            BigDecimal mult = o.effectMultiplier();
            switch (t) {
                case ADD -> {
                    if (tgt != null && qty != null && qty.signum() > 0) {
                        result.merge(tgt, qty, BigDecimal::add);
                    }
                }
                case REMOVE -> {
                    if (src != null) {
                        BigDecimal current = result.getOrDefault(src, BigDecimal.ZERO);
                        BigDecimal next = (qty == null)
                                ? BigDecimal.ZERO
                                : current.subtract(qty).max(BigDecimal.ZERO);
                        result.put(src, next);
                    }
                }
                case REPLACE -> {
                    if (src != null && tgt != null && !src.equals(tgt)) {
                        BigDecimal removed = result.getOrDefault(src, BigDecimal.ZERO);
                        // Preserve the entire calculated source amount, unless
                        // the option declares an explicit replacement quantity.
                        BigDecimal add = (qty != null) ? qty : removed;
                        result.put(src, BigDecimal.ZERO);
                        if (add.signum() > 0) {
                            result.merge(tgt, add, BigDecimal::add);
                        }
                    }
                }
                case MULTIPLY -> {
                    if (src != null && mult != null) {
                        BigDecimal current = result.getOrDefault(src, BigDecimal.ZERO);
                        BigDecimal next = current.multiply(mult).setScale(SCALE, ROUNDING);
                        result.put(src, next);
                    }
                }
                default -> { /* unreachable */ }
            }
        }
        return Collections.unmodifiableMap(result);
    }

    /**
     * Reads the source ingredient for the current option. Post-V041 rows carry
     * it directly; a legacy V039 row for REMOVE/MULTIPLY stored the affected
     * ingredient in {@code targetIngredientId} and REPLACE discovered the
     * source through {@code replacesOptionId}. Both are handled here so a
     * partially-migrated dataset yields the same consumption plan.
     */
    private UUID resolveSource(CustomizationOption o) {
        if (o.sourceIngredientId() != null) {
            return o.sourceIngredientId();
        }
        InventoryEffectType t = o.inventoryEffectType();
        if (t == InventoryEffectType.REMOVE || t == InventoryEffectType.MULTIPLY) {
            return o.targetIngredientId();
        }
        if (t == InventoryEffectType.REPLACE && o.replacesOptionId() != null) {
            CustomizationOption legacy = options.findById(o.replacesOptionId()).orElse(null);
            if (legacy != null) {
                return legacy.targetIngredientId() != null ? legacy.targetIngredientId() : legacy.sourceIngredientId();
            }
        }
        return null;
    }
}
