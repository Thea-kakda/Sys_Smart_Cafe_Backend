package com.smartcoffee.modules.inventory.application;

import com.smartcoffee.common.exception.ValidationException;
import java.math.BigDecimal;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;

/**
 * Deterministic reservation delta engine (Slice 2C §7).
 *
 * <p>Input: the demand map the reservation currently holds (old) and the freshly
 * calculated authoritative demand (new). Output: one signed delta per inventory
 * item, {@code delta = new - old}, with zero deltas omitted and keys sorted so
 * balance rows are always locked in the same order (deadlock safety).
 *
 * <p>Pure and side-effect free: neither input map is mutated, the result is a
 * new sorted map, and decimal precision is carried through untouched (no
 * rounding, no rescaling). A negative demand value is rejected as
 * {@code INVALID_RESERVATION_DELTA} — a holding can never go below zero.
 */
public final class ReservationDeltaEngine {

    private ReservationDeltaEngine() {
    }

    /**
     * @param oldDemand quantity currently held per inventory item (null = none)
     * @param newDemand authoritative demand per inventory item (null = none)
     * @return signed, non-zero deltas keyed by inventory item id, ascending
     */
    public static SortedMap<UUID, BigDecimal> delta(Map<UUID, BigDecimal> oldDemand,
            Map<UUID, BigDecimal> newDemand) {
        Map<UUID, BigDecimal> held = oldDemand == null ? Map.of() : oldDemand;
        Map<UUID, BigDecimal> wanted = newDemand == null ? Map.of() : newDemand;

        TreeSet<UUID> items = new TreeSet<>();
        // Reject a null key BEFORE the sorted set sees it: TreeSet would raise a
        // bare NullPointerException, which is not a business error the API contract
        // can map.
        held.keySet().forEach(item -> items.add(requireItem(item)));
        wanted.keySet().forEach(item -> items.add(requireItem(item)));

        SortedMap<UUID, BigDecimal> deltas = new TreeMap<>();
        for (UUID item : items) {
            BigDecimal before = quantity(held.get(item), "held");
            BigDecimal after = quantity(wanted.get(item), "requested");
            BigDecimal diff = after.subtract(before);
            if (diff.signum() != 0) {
                deltas.put(item, diff);
            }
        }
        return deltas;
    }

    /** True when the map holds no requirement. */
    public static boolean isEmpty(Map<UUID, BigDecimal> demand) {
        return demand == null || demand.isEmpty();
    }

    private static UUID requireItem(UUID item) {
        if (item == null) {
            throw new ValidationException("INVALID_RESERVATION_DELTA", "Inventory item id is required");
        }
        return item;
    }

    private static BigDecimal quantity(BigDecimal value, String side) {
        if (value == null) {
            return BigDecimal.ZERO;
        }
        if (value.signum() < 0) {
            throw new ValidationException("INVALID_RESERVATION_DELTA",
                    "Reservation " + side + " quantity cannot be negative: " + value.toPlainString());
        }
        return value;
    }
}
