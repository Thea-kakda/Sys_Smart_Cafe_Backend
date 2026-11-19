package com.smartcoffee.modules.inventory.application;

import com.smartcoffee.common.exception.ValidationException;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * One cart line's authoritative, normalized inventory demand — the exact output
 * of {@code ConsumptionPlanService}, already expressed per inventory item in that
 * item's base unit and already scaled by the sellable quantity.
 *
 * <p>Immutable and deterministically ordered (ascending inventory-item id) so
 * every consumer locks balance rows in the same sequence. The map is the demand
 * <b>snapshot</b>: once persisted on the reservation it is the only input a
 * release needs, so a later recipe/option edit can never change how much is
 * given back.
 *
 * @param lines                  inventory item id → required quantity (base unit)
 * @param baseUnits              inventory item id → base unit code (readability)
 * @param recipeVersionId        resolved ACTIVE recipe version (null when NONE)
 * @param recipeVersionNumber    its version number (null when NONE)
 * @param resolutionSource       VARIANT / PRODUCT / NONE
 */
public record ReservationDemand(
        Map<UUID, BigDecimal> lines,
        Map<UUID, String> baseUnits,
        UUID recipeVersionId,
        Integer recipeVersionNumber,
        String resolutionSource) {

    public ReservationDemand {
        SortedMap<UUID, BigDecimal> ordered = new TreeMap<>();
        if (lines != null) {
            lines.forEach((item, quantity) -> {
                if (item == null) {
                    throw new ValidationException("INVALID_RESERVATION_DELTA", "Inventory item id is required");
                }
                if (quantity == null || quantity.signum() < 0) {
                    throw new ValidationException("INVALID_RESERVATION_DELTA",
                            "Reservation quantity must be zero or positive for " + item);
                }
                if (quantity.signum() > 0) {
                    ordered.put(item, quantity);
                }
            });
        }
        lines = Collections.unmodifiableMap(ordered);
        baseUnits = baseUnits == null ? Map.of() : Map.copyOf(baseUnits);
    }

    public static ReservationDemand empty() {
        return new ReservationDemand(Map.of(), Map.of(), null, null, "NONE");
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }
}
