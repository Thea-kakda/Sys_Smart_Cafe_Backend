package com.smartcoffee.modules.promotion.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Deterministic eligible-line allocation of one Promotion's order-level
 * discount (Phase B2 §7).
 *
 * <p>Discount is spread proportionally to each eligible line's authoritative
 * subtotal at 4-decimal HALF_UP. Rounding residue goes to the last eligible
 * line in the caller's deterministic (cart item) order, so the sum of line
 * discounts always reconciles exactly with the order discount, no line exceeds
 * its own subtotal and no line is negative.
 */
public final class PromotionDiscountAllocator {

    private static final int SCALE = 4;

    private PromotionDiscountAllocator() {
    }

    public record Line(UUID cartItemId, BigDecimal subtotal) {
    }

    public static Map<UUID, BigDecimal> allocate(BigDecimal discount, List<Line> eligibleLines) {
        BigDecimal target = discount == null ? BigDecimal.ZERO : discount.setScale(SCALE, RoundingMode.HALF_UP);
        if (target.signum() <= 0 || eligibleLines.isEmpty())
            return Map.of();

        BigDecimal total = eligibleLines.stream().map(Line::subtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .setScale(SCALE, RoundingMode.HALF_UP);
        if (total.signum() <= 0)
            throw new IllegalArgumentException("Eligible subtotal must be positive");
        if (target.compareTo(total) > 0)
            throw new IllegalArgumentException("Discount cannot exceed the eligible subtotal");

        int size = eligibleLines.size();
        BigDecimal[] shares = new BigDecimal[size];
        BigDecimal remaining = target;
        for (int i = 0; i < size; i++) {
            Line line = eligibleLines.get(i);
            BigDecimal subtotal = line.subtotal().setScale(SCALE, RoundingMode.HALF_UP);
            BigDecimal share = i == size - 1 ? remaining
                    : target.multiply(subtotal).divide(total, SCALE, RoundingMode.HALF_UP);
            if (i == size - 1)
                share = share.min(subtotal);
            else
                share = share.min(subtotal).min(remaining);
            shares[i] = share;
            remaining = remaining.subtract(share);
        }
        // Residual from per-line rounding can only be a few 0.0001s; the last
        // line takes it first, then any spare capacity is filled backwards.
        for (int i = size - 1; i >= 0 && remaining.signum() > 0; i--) {
            BigDecimal spare = eligibleLines.get(i).subtotal().setScale(SCALE, RoundingMode.HALF_UP)
                    .subtract(shares[i]);
            BigDecimal add = remaining.min(spare);
            shares[i] = shares[i].add(add);
            remaining = remaining.subtract(add);
        }
        if (remaining.signum() > 0)
            throw new IllegalStateException("Discount allocation did not reconcile");

        Map<UUID, BigDecimal> allocated = new LinkedHashMap<>();
        for (int i = 0; i < size; i++) {
            allocated.put(eligibleLines.get(i).cartItemId(), shares[i]);
        }
        return allocated;
    }
}
