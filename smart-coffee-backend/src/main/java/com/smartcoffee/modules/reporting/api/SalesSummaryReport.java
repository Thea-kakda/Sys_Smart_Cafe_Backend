package com.smartcoffee.modules.reporting.api;

import java.time.Instant;
import java.util.List;

/**
 * Sales summary envelope. {@code timezone} states which zone the period buckets
 * were cut in (the branch zone for single-store reports, UTC when the scope
 * spans stores — no fake per-store calendar alignment is claimed). The result
 * is bounded: at most 1000 groups, with {@code truncated} made explicit.
 */
public record SalesSummaryReport(
        Instant from,
        Instant to,
        String grouping,
        String timezone,
        List<SalesSummaryRow> rows,
        boolean truncated,
        String metricBasis) {
}
