package com.smartcoffee.modules.availability;

import java.math.BigDecimal;
import java.util.List;

/**
 * Availability of ONE customization value (Slice 3A, spec §4/§10). Immutable.
 *
 * <p>Semantics:
 * <ul>
 *   <li>{@code selectable} — the option is active and allowed for this product,
 *       so a customer MAY choose it (independent of stock).</li>
 *   <li>{@code available} — choosing it yields a configuration whose required
 *       inventory is satisfiable now (§10). A {@code REMOVE} option never makes
 *       an otherwise-available product unavailable; {@code ADD} availability
 *       depends on the extra ingredient's remaining stock; {@code MULTIPLY}
 *       (e.g. Sugar 0%) with a zero/low factor is available even when the source
 *       ingredient is out of stock.</li>
 *   <li>{@code availableQuantity} — the maximum base servings achievable WITH
 *       this option applied, or {@code null} when the product is not
 *       inventory-tracked (servings not meaningfully calculable).</li>
 *   <li>{@code priceDelta} — the option's additional price (authoritative, loaded
 *       from the option row; never a client-supplied value).</li>
 * </ul>
 */
public record OptionAvailabilityResult(
        java.util.UUID optionId,
        boolean active,
        boolean selectable,
        boolean available,
        Integer availableQuantity,
        BigDecimal priceDelta,
        List<String> reasonCodes) {

    public OptionAvailabilityResult {
        reasonCodes = List.copyOf(reasonCodes);
    }
}
