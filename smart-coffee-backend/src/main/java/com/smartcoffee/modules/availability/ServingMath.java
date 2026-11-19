package com.smartcoffee.modules.availability;

import com.smartcoffee.modules.availability.InventoryAvailabilityReader.StockSnapshot;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.UUID;

/**
 * The single place the availability engine turns a per-sellable-unit requirement map
 * plus stock snapshots into a serving count (Slice 3A, spec §7). Both the base recipe
 * path and the option path call this, so the arithmetic is defined exactly once and is
 * never duplicated inconsistently.
 *
 * <p>{@code availableServings = min over required items of
 * floor(availableStock / perServingRequirement)} where
 * {@code availableStock = currentStock - reservedStock}. Rules honored here:
 * <ul>
 *   <li>BigDecimal only, never {@code double}.</li>
 *   <li>A requirement of zero or less for an item does not constrain the min (and is
 *       never divided by).</li>
 *   <li>Missing balance row ⇒ zero available (never fabricated).</li>
 *   <li>Negative available (data inconsistency) is an anomaly, reported as such rather
 *       than as a negative serving count; the caller marks the result conservatively
 *       unavailable.</li>
 *   <li>The count is clamped to a safe supported integer range.</li>
 * </ul>
 */
final class ServingMath {

    /** Upper clamp so an effectively-unbounded recipe cannot overflow the integer view. */
    static final int MAX_SERVINGS = 1_000_000;

    private ServingMath() {
    }

    /**
     * Result of a servings computation.
     *
     * @param limited   false when there is no required inventory item at all (nothing
     *                  constrains the count — the caller treats it as "not inventory
     *                  limited" and returns a nullable serving estimate).
     * @param servings  the clamped minimum serving count (0..MAX), meaningful only when
     *                  {@code limited} is true.
     * @param anomaly   true when any required item had a negative available quantity
     *                  (current &lt; reserved) — a data-integrity signal, not a real
     *                  stock level.
     * @param bottleneck the ingredient that set the minimum (only when {@code limited}
     *                  and no anomaly), else null. Deterministic (first in map order).
     */
    record Servings(boolean limited, int servings, boolean anomaly, UUID bottleneck) {

        static Servings unlimited() {
            return new Servings(false, 0, false, null);
        }
    }

    static Servings compute(Map<UUID, BigDecimal> perServingRequired, Map<UUID, StockSnapshot> snapshots) {
        boolean limited = false;
        long min = Long.MAX_VALUE;
        UUID bottleneck = null;
        boolean anomaly = false;
        // Caller supplies a deterministically ordered (TreeMap) requirement map, so the
        // first item achieving the minimum is a stable bottleneck.
        for (Map.Entry<UUID, BigDecimal> e : perServingRequired.entrySet()) {
            BigDecimal need = e.getValue();
            if (need == null || need.signum() <= 0) {
                continue; // an omitted / zero line does not constrain servings
            }
            limited = true;
            StockSnapshot snap = snapshots.get(e.getKey());
            BigDecimal available = snap == null ? BigDecimal.ZERO : snap.availableStock();
            if (available.signum() < 0) {
                anomaly = true; // reserved > current: inconsistent, never a negative count
            }
            long itemServings;
            if (available.signum() <= 0) {
                itemServings = 0L;
            } else {
                BigDecimal cap = available.divide(need, 0, RoundingMode.FLOOR);
                itemServings = cap.compareTo(BigDecimal.valueOf(MAX_SERVINGS)) > 0
                        ? MAX_SERVINGS : cap.longValue();
            }
            if (itemServings < min) {
                min = itemServings;
                bottleneck = e.getKey();
            }
        }
        if (!limited) {
            return Servings.unlimited();
        }
        return new Servings(true, (int) min, anomaly, anomaly ? null : bottleneck);
    }


    /**
     * Convenience predicate: is at least {@code quantity} servings available? An anomaly
     * is conservatively treated as NOT satisfiable. "Unlimited" (nothing inventory-
     * limited) is always satisfiable.
     */
    static boolean satisfies(Servings s, int quantity) {
        if (s.anomaly()) {
            return false;
        }
        if (!s.limited()) {
            return true;
        }
        return s.servings() >= quantity;
    }
}
