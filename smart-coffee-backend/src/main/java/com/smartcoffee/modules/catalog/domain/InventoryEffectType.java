package com.smartcoffee.modules.catalog.domain;

/**
 * Inventory effect a customization option applies to a base recipe. Aligns
 * with migration V039 ck_option_effect_type.
 *
 * Semantics:
 *   NONE    — price only, no stock movement (default for pre-existing options).
 *   ADD     — consume effect_quantity of target_ingredient_id on top of base.
 *   REMOVE  — reduce base consumption by effect_quantity of target_ingredient_id.
 *   REPLACE — swap the ingredient consumed by replaces_option_id with this
 *             option's target_ingredient_id (preserves base quantity unless
 *             effect_quantity is set).
 *   MULTIPLY— scale the base ingredient row by effect_multiplier. Used for
 *             sugar percentage (0.00 no sugar / 0.50 / 1.00 / 1.50 extra).
 *
 * The Java mapping lives on CustomizationOption; DB shape guards require the
 * fields each variant needs (see ck_option_effect_shape).
 */
public enum InventoryEffectType {
    NONE,
    ADD,
    REMOVE,
    REPLACE,
    MULTIPLY
}
