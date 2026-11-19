package com.smartcoffee.modules.recipe.application;

/**
 * Why a consumption line exists in a {@link CalculatedConsumptionPlan}. This is a
 * read-only provenance tag for the Slice 2B planning surface; it never drives a
 * stock movement.
 *
 * <ul>
 *   <li>{@code BASE_RECIPE} — quantity came only from a resolved recipe line.</li>
 *   <li>{@code OPTION_ADD} / {@code OPTION_REMOVE} / {@code OPTION_REPLACE} /
 *     {@code OPTION_MULTIPLY} — the line's final quantity was shaped by exactly
 *     one kind of customization inventory effect.</li>
 *   <li>{@code MERGED} — more than one source contributed to the line (e.g. a base
 *     ingredient that an extra also adds), so no single origin applies.</li>
 * </ul>
 */
public enum ConsumptionLineSource {
    BASE_RECIPE,
    OPTION_ADD,
    OPTION_REMOVE,
    OPTION_REPLACE,
    OPTION_MULTIPLY,
    MERGED
}
