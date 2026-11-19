package com.smartcoffee.modules.recipe.domain;

/**
 * How a recipe line's required quantity scales with the recipe yield.
 *
 * <p>Mutually exclusive:
 * <ul>
 *   <li>{@code FIXED} — the persisted {@code quantity_required_base} is consumed
 *     per produced unit regardless of yield. This is exactly the semantics used
 *     by the existing checkout path ({@link RecipeVersionLine#demand(int)}), so
 *     all migrated lines default here to preserve behaviour byte-for-byte.</li>
 *   <li>{@code PROPORTIONAL_TO_YIELD} — the line quantity describes a whole-batch
 *     amount that is divided by yield to obtain a per-unit value. Consumed by
 *     future Slice 2B consumption planning only; not used by checkout in 2A.</li>
 * </ul>
 */
public enum RecipeScalingRule {
    FIXED, PROPORTIONAL_TO_YIELD
}
