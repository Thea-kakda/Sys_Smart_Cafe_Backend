package com.smartcoffee.modules.ordering.application;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.*;

/**
 * Authoritative order arithmetic for checkout (Phase B2 §8).
 *
 * <p>Tax is computed on the taxable amount AFTER the allocated promotion
 * discount, per line, at the line's own authoritative catalog tax rate. The
 * order total is therefore {@code subtotal - discount + tax}, and the per-line
 * discounts are exactly the amount the allocator distributed, so summing lines
 * reconciles with the order without a second rounding pass.
 */
public final class OrderPricing {

    private static final int SCALE = 4;

    private OrderPricing() {
    }

    public record Line(UUID cartItemId, BigDecimal subtotal, BigDecimal taxRate) {
    }

    public record PricedLine(BigDecimal discountAmount, BigDecimal taxableAmount, BigDecimal taxAmount,
            BigDecimal lineTotal) {
    }

    public record Result(BigDecimal subtotal, BigDecimal discountAmount, BigDecimal taxAmount, BigDecimal totalAmount,
            Map<UUID, PricedLine> lines) {
    }

    public static Result price(List<Line> lines, Map<UUID, BigDecimal> lineDiscounts) {
        Map<UUID, BigDecimal> discounts = lineDiscounts == null ? Map.of() : lineDiscounts;
        Map<UUID, PricedLine> priced = new LinkedHashMap<>();
        BigDecimal subtotal = BigDecimal.ZERO, discountTotal = BigDecimal.ZERO, taxTotal = BigDecimal.ZERO;
        for (Line line : lines) {
            BigDecimal lineSubtotal = line.subtotal().setScale(SCALE, RoundingMode.HALF_UP);
            BigDecimal discount = discounts.getOrDefault(line.cartItemId(), BigDecimal.ZERO)
                    .setScale(SCALE, RoundingMode.HALF_UP);
            if (discount.signum() < 0 || discount.compareTo(lineSubtotal) > 0)
                throw new IllegalArgumentException("Line discount must be within the line subtotal");
            BigDecimal taxable = lineSubtotal.subtract(discount);
            BigDecimal lineTax = taxable.multiply(line.taxRate()).setScale(SCALE, RoundingMode.HALF_UP);
            priced.put(line.cartItemId(), new PricedLine(discount, taxable, lineTax, taxable.add(lineTax)));
            subtotal = subtotal.add(lineSubtotal);
            discountTotal = discountTotal.add(discount);
            taxTotal = taxTotal.add(lineTax);
        }
        subtotal = subtotal.setScale(SCALE, RoundingMode.HALF_UP);
        discountTotal = discountTotal.setScale(SCALE, RoundingMode.HALF_UP);
        taxTotal = taxTotal.setScale(SCALE, RoundingMode.HALF_UP);
        return new Result(subtotal, discountTotal, taxTotal, subtotal.subtract(discountTotal).add(taxTotal), priced);
    }
}
