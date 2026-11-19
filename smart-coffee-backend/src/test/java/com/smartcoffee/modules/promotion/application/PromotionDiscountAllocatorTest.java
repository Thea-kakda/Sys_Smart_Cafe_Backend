package com.smartcoffee.modules.promotion.application;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Phase B2 §7 allocation contract: eligible lines only, proportional to their
 * authoritative subtotals, exact reconciliation, deterministic remainder,
 * never negative and never above a line.
 */
class PromotionDiscountAllocatorTest {

    private static final UUID A = UUID.randomUUID(), B = UUID.randomUUID(), C = UUID.randomUUID();

    @Test
    void noDiscountAllocatesNothing() {
        assertTrue(PromotionDiscountAllocator.allocate(BigDecimal.ZERO, lines(line(A, "10"))).isEmpty());
        assertTrue(PromotionDiscountAllocator.allocate(null, lines(line(A, "10"))).isEmpty());
        assertTrue(PromotionDiscountAllocator.allocate(new BigDecimal("-1"), lines(line(A, "10"))).isEmpty());
    }

    @Test
    void noEligibleLinesAllocatesNothing() {
        assertTrue(PromotionDiscountAllocator.allocate(new BigDecimal("1"), List.of()).isEmpty());
    }

    @Test
    void singleLineTakesTheWholeDiscount() {
        var result = PromotionDiscountAllocator.allocate(new BigDecimal("2.50"), lines(line(A, "10")));
        assertEquals(0, new BigDecimal("2.5000").compareTo(result.get(A)));
    }

    @Test
    void discountIsProportionalToLineSubtotals() {
        var result = PromotionDiscountAllocator.allocate(new BigDecimal("8.00"),
                lines(line(A, "10"), line(B, "30")));
        assertEquals(0, new BigDecimal("2.0000").compareTo(result.get(A)));
        assertEquals(0, new BigDecimal("6.0000").compareTo(result.get(B)));
    }

    @Test
    void roundingRemainderGoesToTheLastEligibleLine() {
        var result = PromotionDiscountAllocator.allocate(BigDecimal.ONE,
                lines(line(A, "10"), line(B, "10"), line(C, "10")));
        assertEquals(0, new BigDecimal("0.3333").compareTo(result.get(A)));
        assertEquals(0, new BigDecimal("0.3333").compareTo(result.get(B)));
        assertEquals(0, new BigDecimal("0.3334").compareTo(result.get(C)));
        assertEquals(0, BigDecimal.ONE.compareTo(sum(result)));
    }

    @Test
    void oddPenniesStillReconcileExactly() {
        var result = PromotionDiscountAllocator.allocate(new BigDecimal("3.33"),
                lines(line(A, "9.99"), line(B, "4.99"), line(C, "0.02")));
        assertEquals(0, new BigDecimal("3.33").compareTo(sum(result)));
        for (var entry : result.entrySet())
            assertTrue(entry.getValue().signum() >= 0);
    }

    @Test
    void discountEqualToEligibleSubtotalFullyDiscountsEveryLine() {
        var result = PromotionDiscountAllocator.allocate(new BigDecimal("15"),
                lines(line(A, "10"), line(B, "5")));
        assertEquals(0, new BigDecimal("10").compareTo(result.get(A)));
        assertEquals(0, new BigDecimal("5").compareTo(result.get(B)));
    }

    @Test
    void noLineExceedsItsOwnSubtotal() {
        var result = PromotionDiscountAllocator.allocate(new BigDecimal("0.03"),
                lines(line(A, "0.01"), line(B, "0.01"), line(C, "0.01")));
        assertEquals(0, new BigDecimal("0.01").compareTo(result.get(A)));
        assertEquals(0, new BigDecimal("0.01").compareTo(result.get(B)));
        assertEquals(0, new BigDecimal("0.01").compareTo(result.get(C)));
    }

    @Test
    void discountAboveTheEligibleSubtotalIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> PromotionDiscountAllocator.allocate(new BigDecimal("11"), lines(line(A, "10"))));
    }

    @Test
    void nonPositiveEligibleSubtotalIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> PromotionDiscountAllocator.allocate(BigDecimal.ONE, lines(line(A, "0"))));
    }

    @Test
    void allocationFollowsTheCallersDeterministicLineOrder() {
        var first = PromotionDiscountAllocator.allocate(BigDecimal.ONE,
                lines(line(B, "5"), line(A, "5")));
        var second = PromotionDiscountAllocator.allocate(BigDecimal.ONE,
                lines(line(A, "5"), line(B, "5")));
        assertEquals(0, first.get(B).compareTo(second.get(A)), "the last listed line takes the residue");
        assertEquals(0, first.get(A).compareTo(second.get(B)));
    }

    private static BigDecimal sum(Map<UUID, BigDecimal> allocation) {
        return allocation.values().stream().reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static List<PromotionDiscountAllocator.Line> lines(PromotionDiscountAllocator.Line... lines) {
        return List.of(lines);
    }

    private static PromotionDiscountAllocator.Line line(UUID id, String subtotal) {
        return new PromotionDiscountAllocator.Line(id, new BigDecimal(subtotal));
    }
}
