package com.smartcoffee.modules.availability;

/**
 * Machine-readable availability reason codes (Slice 3A, spec §5). These are
 * STABLE contract strings: they are what future Dashboard / POS / Customer / QR /
 * Delivery channels switch on, so the literal names are frozen. A result may
 * carry several reasons (e.g. a variant that is both out of stock and missing a
 * recipe override).
 *
 * <p>Grouped by the entity they qualify:
 * <ul>
 *   <li>Product / store master + policy reasons,</li>
 *   <li>Variant resolution / stock reasons,</li>
 *   <li>Option group / value selection reasons,</li>
 *   <li>Operational / data-integrity reasons.</li>
 * </ul>
 *
 * <p>Codes are exposed to clients via {@link #code()} (== {@link #name()}). Never
 * place raw SQL, exception messages, costs or supplier data in a reason — those
 * belong to the request-level error contract (§20), not to normal unavailability.
 */
public enum AvailabilityReason {

    // --- Product / store master + policy ---
    PRODUCT_NOT_FOUND,
    PRODUCT_INACTIVE,
    PRODUCT_NOT_LISTED,
    PRODUCT_MANUALLY_SOLD_OUT,
    PRODUCT_OUTSIDE_SCHEDULE,
    PRODUCT_CONFIGURATION_UNAVAILABLE,
    PRODUCT_OUT_OF_STOCK,
    ACTIVE_RECIPE_REQUIRED,
    INVENTORY_DATA_INCONSISTENT,

    // --- Variant ---
    VARIANT_INACTIVE,
    VARIANT_NOT_OFFERED,
    VARIANT_RECIPE_UNAVAILABLE,
    VARIANT_OUT_OF_STOCK,
    REQUIRED_PACKAGING_UNAVAILABLE,

    // --- Option group / value ---
    OPTION_INACTIVE,
    OPTION_NOT_ALLOWED,
    OPTION_OUT_OF_STOCK,
    OPTION_SOURCE_INGREDIENT_UNAVAILABLE,
    OPTION_TARGET_INGREDIENT_UNAVAILABLE,
    OPTION_EFFECT_CONFLICT,
    REQUIRED_OPTION_GROUP_UNAVAILABLE,
    REQUIRED_OPTION_SELECTION,

    // --- Operational ---
    AVAILABILITY_CALCULATION_FAILED,
    RECONCILIATION_CRITICAL,
    UNIT_CONVERSION_ERROR,
    AVAILABILITY_COMPLEXITY_LIMIT;

    /** The stable wire string. Equal to the enum name by design. */
    public String code() {
        return name();
    }
}
