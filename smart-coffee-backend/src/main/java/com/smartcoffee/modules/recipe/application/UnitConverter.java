package com.smartcoffee.modules.recipe.application;

import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.modules.inventory.domain.UnitOfMeasure;
import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Decimal-safe, single-dimension unit normalization for Slice 2B planning. No
 * {@code double} / {@code float} is ever used.
 *
 * <p>Every {@link UnitOfMeasure} carries a {@code conversionFactorToBase} relative
 * to its {@link com.smartcoffee.modules.inventory.domain.UnitType} dimension, so a
 * quantity expressed in unit {@code U} converts to unit {@code B} (same dimension)
 * by {@code q * factor(U) / factor(B)}. Cross-dimension conversion (mass ↔ volume)
 * is rejected — the model has no density support.
 *
 * <p>When the source unit is absent the quantity is treated as already expressed in
 * the base unit and returned untouched. That identity path is exactly what the
 * historical checkout relies on, so plans stay byte-for-byte compatible for
 * migrated recipes (whose line {@code unit_id} is null).
 */
final class UnitConverter {

    /** Intermediate guard scale for the division; final rounding happens later. */
    private static final int INTERMEDIATE_SCALE = 10;

    private UnitConverter() {
    }

    /**
     * @param quantity the amount in {@code fromUnit} (or already base when null)
     * @param fromUnit unit the quantity is authored in; may be null
     * @param baseUnit the ingredient's base unit to normalize into
     * @return the equivalent quantity in the base unit
     */
    static BigDecimal toBaseUnit(BigDecimal quantity, UnitOfMeasure fromUnit, UnitOfMeasure baseUnit) {
        if (quantity == null) {
            throw new ValidationException("INVALID_RECIPE_QUANTITY", "Recipe quantity is required");
        }
        if (fromUnit == null) {
            // Authored directly in the ingredient base unit.
            return quantity;
        }
        if (baseUnit == null) {
            throw new ValidationException("UNIT_NOT_FOUND", "Ingredient base unit could not be resolved");
        }
        if (fromUnit.type() != baseUnit.type()) {
            throw new ValidationException("INCOMPATIBLE_RECIPE_UNIT",
                    "Cannot convert " + fromUnit.code() + " (" + fromUnit.type() + ") to "
                            + baseUnit.code() + " (" + baseUnit.type() + ")");
        }
        BigDecimal fromFactor = fromUnit.factor();
        BigDecimal toFactor = baseUnit.factor();
        if (fromFactor == null || fromFactor.signum() <= 0
                || toFactor == null || toFactor.signum() <= 0) {
            throw new ValidationException("INVALID_CONVERSION_FACTOR",
                    "Unit conversion factors must be positive");
        }
        // q * factor(from) / factor(to)
        return quantity.multiply(fromFactor).divide(toFactor, INTERMEDIATE_SCALE, RoundingMode.HALF_UP);
    }
}
