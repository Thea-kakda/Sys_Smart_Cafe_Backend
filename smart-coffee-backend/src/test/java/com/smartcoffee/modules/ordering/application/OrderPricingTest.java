package com.smartcoffee.modules.ordering.application;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.*;
import org.junit.jupiter.api.Test;

/** Phase B2 §8 arithmetic: discount pre-tax, tax on the taxable amount, exact totals. */
class OrderPricingTest {

    private static final UUID A = UUID.randomUUID(), B = UUID.randomUUID();

    @Test
    void noPromotionKeepsTheExistingArithmetic() {
        var result = OrderPricing.price(List.of(line(A, "10", "0.10")), Map.of());
        assertEquals(0, new BigDecimal("10.0000").compareTo(result.subtotal()));
        assertEquals(0, BigDecimal.ZERO.setScale(4).compareTo(result.discountAmount()));
        assertEquals(0, new BigDecimal("1.0000").compareTo(result.taxAmount()));
        assertEquals(0, new BigDecimal("11.0000").compareTo(result.totalAmount()));
        assertEquals(0, new BigDecimal("11.0000").compareTo(result.lines().get(A).lineTotal()));
    }

    @Test
    void taxIsComputedAfterTheLineDiscount() {
        var result = OrderPricing.price(List.of(line(A, "10", "0.10")), Map.of(A, new BigDecimal("2")));
        assertEquals(0, new BigDecimal("2.0000").compareTo(result.lines().get(A).discountAmount()));
        assertEquals(0, new BigDecimal("8.0000").compareTo(result.lines().get(A).taxableAmount()));
        assertEquals(0, new BigDecimal("0.8000").compareTo(result.lines().get(A).taxAmount()));
        assertEquals(0, new BigDecimal("8.8000").compareTo(result.lines().get(A).lineTotal()));
        assertEquals(0, new BigDecimal("8.8000").compareTo(result.totalAmount()));
    }

    @Test
    void mixedEligibilityDiscountsOnlyTheDiscountedLine() {
        var result = OrderPricing.price(List.of(line(A, "10", "0.10"), line(B, "5", "0.10")),
                Map.of(A, new BigDecimal("4")));
        assertEquals(0, BigDecimal.ZERO.setScale(4).compareTo(result.lines().get(B).discountAmount()));
        assertEquals(0, new BigDecimal("0.5000").compareTo(result.lines().get(B).taxAmount()));
        assertEquals(0, new BigDecimal("0.6000").compareTo(result.lines().get(A).taxAmount()));
        assertEquals(0, new BigDecimal("12.1000").compareTo(result.totalAmount()));
        assertEquals(0, new BigDecimal("4.0000").compareTo(result.discountAmount()));
    }

    @Test
    void multipleTaxRatesAreAppliedPerLine() {
        var result = OrderPricing.price(List.of(line(A, "10", "0.10"), line(B, "10", "0.07")), Map.of());
        assertEquals(0, new BigDecimal("1.0000").compareTo(result.lines().get(A).taxAmount()));
        assertEquals(0, new BigDecimal("0.7000").compareTo(result.lines().get(B).taxAmount()));
        assertEquals(0, new BigDecimal("1.7000").compareTo(result.taxAmount()));
    }

    @Test
    void zeroTaxRateProducesZeroTax() {
        var result = OrderPricing.price(List.of(line(A, "10", "0")), Map.of(A, new BigDecimal("1")));
        assertEquals(0, BigDecimal.ZERO.setScale(4).compareTo(result.taxAmount()));
        assertEquals(0, new BigDecimal("9.0000").compareTo(result.totalAmount()));
    }

    @Test
    void taxRoundsHalfUpAtFourDecimals() {
        // 1.1111 * 0.075 = 0.0833325 -> 0.0833
        var result = OrderPricing.price(List.of(line(A, "1.1111", "0.075")), Map.of());
        assertEquals(0, new BigDecimal("0.0833").compareTo(result.taxAmount()));
    }

    @Test
    void fullDiscountLeavesZeroTaxAndZeroTotal() {
        var result = OrderPricing.price(List.of(line(A, "10", "0.10")), Map.of(A, new BigDecimal("10")));
        assertEquals(0, BigDecimal.ZERO.setScale(4).compareTo(result.lines().get(A).taxAmount()));
        assertEquals(0, BigDecimal.ZERO.setScale(4).compareTo(result.totalAmount()));
    }

    @Test
    void lineTotalsSumToTheOrderTotal() {
        var result = OrderPricing.price(List.of(line(A, "9.99", "0.10"), line(B, "4.99", "0.07")),
                Map.of(A, new BigDecimal("1.23")));
        BigDecimal lineSum = result.lines().values().stream().map(OrderPricing.PricedLine::lineTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertEquals(0, lineSum.compareTo(result.totalAmount()));
        assertEquals(0, new BigDecimal("14.9800").compareTo(result.subtotal()));
        assertEquals(0, new BigDecimal("1.2300").compareTo(result.discountAmount()));
    }

    @Test
    void discountAboveTheLineSubtotalIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> OrderPricing.price(List.of(line(A, "10", "0.10")), Map.of(A, new BigDecimal("10.01"))));
    }

    @Test
    void negativeDiscountIsRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> OrderPricing.price(List.of(line(A, "10", "0.10")), Map.of(A, new BigDecimal("-0.01"))));
    }

    private static OrderPricing.Line line(UUID id, String subtotal, String taxRate) {
        return new OrderPricing.Line(id, new BigDecimal(subtotal), new BigDecimal(taxRate));
    }
}
