package com.smartcoffee.modules.catalog.application;

import com.smartcoffee.modules.catalog.domain.InventoryEffectType;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * The single authoritative "effective customization" shape (Slice 4A Round 2,
 * §4). Every consumer that answers "what can this product actually be customized
 * with, at what limits and prices" reads through this — the management read,
 * POS/Customer catalog detail, the cart validator, the availability option
 * evaluator and the consumption planner. Before Round 2 each of those resolved
 * the master group's limits and every active option independently, so a
 * Dashboard-configured per-product override (min/max, allowed subset, default,
 * price) would have been silently ignored everywhere except the editor. This type
 * is the one place that merges the {@code product_customization_groups} override
 * row and the {@code product_customization_options} allow-list rows over the
 * master {@code CustomizationGroup}/{@code CustomizationOption} rows.
 *
 * <p>Instances are produced only by {@link EffectiveCustomizationResolver}; the
 * records are plain data so consumers map them straight into their own response
 * shapes without re-deriving any inheritance rule.
 */
public final class EffectiveCustomization {

    private EffectiveCustomization() {}

    /**
     * One effective group attachment.
     *
     * @param groupId            master group id.
     * @param code               master group code.
     * @param name               master group name.
     * @param selectionType      SINGLE / MULTIPLE (never overridden per product).
     * @param groupActive        master group active flag.
     * @param required           the per-product required flag from the attachment row.
     * @param minimumSelections  effective minimum (override, else master).
     * @param maximumSelections  effective maximum (override, else master; may be {@code null} = unbounded).
     * @param defaultOptionId    per-product default (may be {@code null}).
     * @param displayOrder       per-product ordering from the attachment row.
     * @param relationshipVersion the V049 independent attachment lock token.
     * @param options            effective allowed options, in effective display order.
     */
    public record Group(
            UUID groupId,
            String code,
            String name,
            String selectionType,
            boolean groupActive,
            boolean required,
            int minimumSelections,
            Integer maximumSelections,
            UUID defaultOptionId,
            int displayOrder,
            long relationshipVersion,
            List<Option> options) {}

    /**
     * One effective option of a group.
     *
     * @param optionId       master option id.
     * @param code           master option code.
     * @param name           master option name.
     * @param masterActive   master option active flag (an inactive option never appears in the effective set).
     * @param masterPrice    the master {@code additional_price}.
     * @param priceOverride  the per-product override, or {@code null} when none is set.
     * @param effectivePrice {@code priceOverride} when present, otherwise {@code masterPrice}.
     * @param displayOrder   effective ordering (per-product allow-list order, else master order).
     * @param isDefault      whether this option is the group's per-product default.
     * @param effectType     inventory effect type (from the master option).
     * @param sourceIngredientId effect source (from the master option).
     * @param targetIngredientId effect target (from the master option).
     * @param effectQuantity effect quantity (from the master option).
     * @param effectMultiplier effect multiplier (from the master option).
     * @param replacesOptionId replaced-option id (from the master option).
     * @param optionVersion  master option optimistic-lock token.
     */
    public record Option(
            UUID optionId,
            String code,
            String name,
            boolean masterActive,
            BigDecimal masterPrice,
            BigDecimal priceOverride,
            BigDecimal effectivePrice,
            int displayOrder,
            boolean isDefault,
            InventoryEffectType effectType,
            UUID sourceIngredientId,
            UUID targetIngredientId,
            BigDecimal effectQuantity,
            BigDecimal effectMultiplier,
            UUID replacesOptionId,
            long optionVersion) {}
}
