package com.smartcoffee.modules.reporting.api;

import java.math.BigDecimal;

/**
 * One (period, currency) sales bucket. All money columns aggregate final PAID
 * orders only; orderCount intentionally counts every order in the window.
 * Never mixes currencies: currencyCode is part of the group key.
 */
public record SalesSummaryRow(
        String period,
        String currencyCode,
        long orderCount,
        long paidOrderCount,
        BigDecimal grossSales,
        BigDecimal discounts,
        BigDecimal tax,
        BigDecimal deliveryFees,
        BigDecimal rounding,
        BigDecimal netSales,
        BigDecimal averageOrderValue) {
}
