package com.smartcoffee.modules.reporting.application;

import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.StoreAuthorizationService;
import com.smartcoffee.modules.reporting.api.InventoryConsumptionReport;
import com.smartcoffee.modules.reporting.api.InventoryConsumptionRow;
import com.smartcoffee.modules.reporting.api.SalesSummaryReport;
import com.smartcoffee.modules.reporting.api.SalesSummaryRow;
import com.smartcoffee.modules.reporting.persistence.ReportQueryRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * BI report reads (Master Prompt §10). Every metric derives from persisted
 * order columns through database aggregation — nothing is fabricated and no
 * order rows are streamed into memory. Definitions, stated once and returned
 * with every report:
 *
 * - orderCount: every order placed inside the window (any status/payment),
 *   so unpaid/cancelled volume stays visible;
 * - all money aggregates (grossSales, discounts, tax, deliveryFees, rounding,
 *   netSales) count ONLY payment_status = 'PAID' — the verified final state.
 *   Unpaid (PENDING), FAILED, and REFUNDED orders contribute zero revenue;
 *   cancellations without payment are likewise excluded from money columns;
 * - grossSales = SUM(subtotal) (pre-discount), netSales = SUM(total_amount),
 *   averageOrderValue = netSales / paidOrderCount (null when nothing paid);
 * - currencies are never summed across: currency is part of the group key and
 *   an optional single-currency filter is supported (USD/KHR only — the
 *   currencies the money layer reasons about);
 * - period buckets are cut in the branch timezone for single-store reports
 *   and in UTC otherwise (flagged in the response; no fake alignment).
 */
@Service
public class ReportQueryService {

    private static final UUID NIL_UUID = new UUID(0, 0);
    private static final Set<String> GROUPINGS = Set.of("DAY", "WEEK", "MONTH");
    private static final Set<String> SUPPORTED_CURRENCIES = Set.of("USD", "KHR");
    private static final String METRIC_BASIS = "money columns aggregate payment_status='PAID' orders only; "
            + "grossSales=SUM(subtotal), netSales=SUM(total_amount), averageOrderValue=netSales/paidOrderCount; "
            + "unpaid/failed/refunded excluded from revenue; currency grouping prevents mixed sums";
    private static final String COGS_GAP = "BACKEND API GAP: historical COGS is not reconstructable — ingredient "
            + "costs are current-only (standard_cost/average_unit_cost), ledger unit_cost is nullable and "
            + "currency-less, and no immutable per-sale cost snapshot exists. Gross margin is therefore "
            + "unavailable and no approximated values are reported.";

    private final ReportQueryRepository reports;
    private final StoreAuthorizationService storeAuth;

    public ReportQueryService(ReportQueryRepository reports, StoreAuthorizationService storeAuth) {
        this.reports = reports;
        this.storeAuth = storeAuth;
    }

    @Transactional(readOnly = true)
    public SalesSummaryReport salesSummary(CurrentActor actor, UUID storeId, Instant from, Instant to,
            String grouping, String currencyCode) {
        Scope scope = resolveScope(actor, storeId);
        validateRange(from, to);
        String unit = normalizeGrouping(grouping);
        String currency = normalizeCurrency(currencyCode);

        // Bucket timezone: the branch's own zone when exactly one store is in
        // scope; UTC (explicitly reported) when the window spans stores with
        // potentially different zones. Multi-store reports must not pretend to
        // align local calendars that were never verified.
        String timezone = scope.storeId() != null ? reports.storeTimezone(scope.storeId()) : "UTC";

        List<SalesSummaryRow> rows = reports
                .salesSummary(scope.all(), scope.storeIds(), currency != null, currency == null ? "" : currency,
                        from, to, unit, timezone)
                .stream().map(ReportQueryService::toRow).toList();
        return new SalesSummaryReport(from, to, unit, timezone, rows, rows.size() >= 1000, METRIC_BASIS);
    }

    @Transactional(readOnly = true)
    public InventoryConsumptionReport inventoryConsumption(CurrentActor actor, UUID storeId, Instant from,
            Instant to) {
        Scope scope = resolveScope(actor, storeId);
        validateRange(from, to);

        List<InventoryConsumptionRow> rows = reports.inventoryConsumption(scope.all(), scope.storeIds(), from, to)
                .stream()
                .map(r -> new InventoryConsumptionRow(r.getProductName(), r.getIngredientName(), r.getUnitCode(),
                        r.getUnitsConsumed()))
                .toList();
        long missingRecipe = reports.countItemsWithoutRecipe(scope.all(), scope.storeIds(), from, to);
        return new InventoryConsumptionReport(from, to, rows, missingRecipe, rows.size() >= 1000, false, false,
                COGS_GAP);
    }

    // ------------------------------------------------------------
    // Scope + parameter guards
    // ------------------------------------------------------------

    /** Store scope is mandatory (§10.3): every report runs against a bounded store set. */
    private Scope resolveScope(CurrentActor actor, UUID storeId) {
        if (storeId != null) {
            storeAuth.requireStore(actor, storeId);
            return new Scope(false, List.of(storeId), storeId);
        }
        if (actor.globalAccess()) {
            return new Scope(true, List.of(NIL_UUID), null);
        }
        Set<UUID> mine = actor.storeIds();
        if (mine.isEmpty()) {
            // No assignable scope: the NIL sentinel matches no real store, so
            // every aggregate comes back empty (same convention as admin search).
            return new Scope(false, List.of(NIL_UUID), null);
        }
        return new Scope(false, List.copyOf(mine), mine.size() == 1 ? mine.iterator().next() : null);
    }

    private record Scope(boolean all, List<UUID> storeIds, UUID storeId) {
    }

    private void validateRange(Instant from, Instant to) {
        if (from == null || to == null) {
            throw new ValidationException("MISSING_DATE_RANGE", "Both from and to are required for reports");
        }
        if (!to.isAfter(from)) {
            throw new ValidationException("INVALID_DATE_RANGE", "to must be after from");
        }
    }

    private String normalizeGrouping(String grouping) {
        String unit = grouping == null ? "DAY" : grouping.trim().toUpperCase(Locale.ROOT);
        if (!GROUPINGS.contains(unit)) {
            throw new ValidationException("INVALID_GROUPING", "Grouping must be DAY, WEEK, or MONTH");
        }
        return unit;
    }

    private String normalizeCurrency(String currencyCode) {
        if (currencyCode == null || currencyCode.isBlank()) {
            return null;
        }
        String currency = currencyCode.trim().toUpperCase(Locale.ROOT);
        if (!SUPPORTED_CURRENCIES.contains(currency)) {
            throw new ValidationException("UNSUPPORTED_CURRENCY", "Currency is not supported");
        }
        return currency;
    }

    private static SalesSummaryRow toRow(ReportQueryRepository.SalesRowProjection p) {
        BigDecimal avg = p.getPaidOrderCount() > 0
                ? p.getNetSales().divide(BigDecimal.valueOf(p.getPaidOrderCount()), 4, RoundingMode.HALF_UP)
                : null;
        return new SalesSummaryRow(p.getPeriod(), p.getCurrencyCode(), p.getOrderCount(), p.getPaidOrderCount(),
                p.getGrossSales(), p.getDiscounts(), p.getTax(), p.getDeliveryFees(), p.getRounding(),
                p.getNetSales(), avg);
    }
}
