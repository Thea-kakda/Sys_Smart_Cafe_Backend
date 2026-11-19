package com.smartcoffee.modules.reporting;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.modules.organization.api.CreateStoreRequest;
import com.smartcoffee.modules.organization.application.StoreManagementService;
import com.smartcoffee.modules.reporting.api.InventoryConsumptionReport;
import com.smartcoffee.modules.reporting.api.SalesSummaryReport;
import com.smartcoffee.modules.reporting.api.SalesSummaryRow;
import com.smartcoffee.modules.reporting.application.ReportQueryService;
import com.smartcoffee.testsupport.IntegrationTestBase;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * BI report verification against a real migrated database (V027 in play).
 * Proves the §10 promises: PAID-only money columns, cancelled/unpaid/refunded
 * volume visible but never summed into revenue, currency grouping (no mixed
 * sums ever possible), period buckets cut in the branch timezone (the
 * timezone-boundary test), mandatory bounded ranges, store scoping, and the
 * quantity-only inventory consumption report with the COGS/gross-margin
 * BACKEND API GAP declared instead of approximated.
 */
class ReportManagementAccessIT extends IntegrationTestBase {

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    CurrentActorProvider actors;
    @Autowired
    StoreManagementService storeManagement;
    @Autowired
    ReportQueryService service;

    private static final String SUPER_ADMIN_ROLE_ID = "00000000-0000-0000-0000-000000000002";
    private static final Instant MAY1 = Instant.parse("2026-05-01T00:00:00Z");
    private static final Instant MAY3 = Instant.parse("2026-05-03T00:00:00Z");

    private UUID seedUser(String email) {
        UUID userId = UUID.randomUUID();
        jdbc.update("insert into identity.users (user_id, full_name, email, normalized_email, preferred_language, "
                + "status, security_version, created_at, updated_at, version) values (?,?,?,?,?,'ACTIVE',0,now(),now(),0)",
                userId, "Test User", email, email, "en-US");
        return userId;
    }

    private void authenticate(UUID userId) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(userId.toString(), null, List.of()));
    }

    private UUID seedGlobalAdmin() {
        UUID userId = seedUser("admin-" + UUID.randomUUID() + "@smart.coffee");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, assigned_at) "
                + "values (?,?, '" + SUPER_ADMIN_ROLE_ID + "', null, ?)", UUID.randomUUID(), userId,
                java.sql.Timestamp.from(Instant.now()));
        authenticate(userId);
        return userId;
    }

    private UUID roleIdByCode(String code) {
        return UUID.fromString(jdbc.queryForObject("select role_id from identity.roles where role_code = ?",
                String.class, code));
    }

    private UUID newStore(CurrentActor actor) {
        String code = "RPT" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
        return storeManagement.create(actor, new CreateStoreRequest(code, "Report Branch", "Addr", null,
                "Asia/Phnom_Penh", "USD", "km-KH", true)).storeId();
    }

    /** Full money-column order insert; every amount explicit so sums are exact. */
    private UUID insertOrder(String number, UUID storeId, String status, String paymentStatus, String currency,
            String subtotal, String discount, String tax, String delivery, String rounding, String total,
            String isoTs) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into ordering.orders (order_id, order_number, store_id, source, fulfillment_type, status, "
                + "payment_status, currency_code, subtotal, discount_amount, tax_amount, delivery_fee, "
                + "rounding_amount, total_amount, total_quantity, ordered_at, updated_at) "
                + "values (?::uuid, ?, ?::uuid, 'POS', 'DINE_IN', ?, ?, ?, ?, ?, ?, ?, ?, ?, 2, ?::timestamptz, "
                + "?::timestamptz)", id.toString(), number, storeId.toString(), status, paymentStatus, currency,
                new BigDecimal(subtotal), new BigDecimal(discount), new BigDecimal(tax), new BigDecimal(delivery),
                new BigDecimal(rounding), new BigDecimal(total), isoTs, isoTs);
        return id;
    }

    private void insertOrderItem(UUID orderId, UUID productId, UUID recipeVersionId, String name, int quantity) {
        jdbc.update("insert into ordering.order_items (order_item_id, order_id, product_id, recipe_version_id, "
                + "product_code_snapshot, product_name_snapshot, quantity, unit_base_price, "
                + "unit_customization_price, line_total, created_at) "
                + "values (?::uuid, ?::uuid, ?::uuid, ?::uuid, 'SNAP', ?, ?, 1, 0, ?, now())",
                UUID.randomUUID().toString(), orderId.toString(),
                productId == null ? null : productId.toString(),
                recipeVersionId == null ? null : recipeVersionId.toString(), name, quantity,
                BigDecimal.valueOf(quantity));
    }

    private static SalesSummaryRow row(List<SalesSummaryRow> rows, String period, String currency) {
        return rows.stream().filter(r -> r.period().equals(period) && r.currencyCode().equals(currency)).findFirst()
                .orElseThrow(() -> new AssertionError("no row for " + period + "/" + currency + " in " + rows));
    }

    private static void eq(String expected, BigDecimal actual) {
        assertEquals(0, new BigDecimal(expected).compareTo(actual));
    }

    @Test
    void salesSummaryAggregatesPaidOnlyPerCurrencyInStoreTimezone() {
        seedGlobalAdmin();
        CurrentActor admin = actors.require();
        UUID storeA = newStore(admin);

        // Local day 2026-05-01 (+07): two PAID USD orders, one PENDING, one REFUNDED.
        insertOrder("RPT-P1", storeA, "COMPLETED", "PAID", "USD", "10", "1", "2", "0.5", "0.01", "11.51",
                "2026-05-01T02:00:00Z");
        insertOrder("RPT-P2", storeA, "COMPLETED", "PAID", "USD", "6", "0", "1", "0", "0", "7",
                "2026-05-01T03:00:00Z");
        insertOrder("RPT-W1", storeA, "AWAITING_PAYMENT", "PENDING", "USD", "50", "0", "0", "0", "0", "50",
                "2026-05-01T04:00:00Z");
        insertOrder("RPT-R1", storeA, "COMPLETED", "REFUNDED", "USD", "99", "0", "0", "0", "0", "99",
                "2026-05-01T05:00:00Z");
        // TIMEZONE BOUNDARY: 20:00Z on May 1 is 03:00 local on May 2 (+07).
        insertOrder("RPT-P3", storeA, "COMPLETED", "PAID", "USD", "4", "0", "0", "0", "0", "4",
                "2026-05-01T20:00:00Z");
        // Second currency gets its own row — never blended.
        insertOrder("RPT-K1", storeA, "COMPLETED", "PAID", "KHR", "20000", "0", "0", "0", "0", "20000",
                "2026-05-01T06:00:00Z");

        SalesSummaryReport day = service.salesSummary(admin, storeA, MAY1, MAY3, "DAY", null);
        assertEquals("Asia/Phnom_Penh", day.timezone());

        SalesSummaryRow may1usd = row(day.rows(), "2026-05-01", "USD");
        assertEquals(4, may1usd.orderCount()); // paid + pending + refunded all counted
        assertEquals(2, may1usd.paidOrderCount()); // money basis: PAID only
        eq("16", may1usd.grossSales()); // 10 + 6 — not 50, not 99
        eq("1", may1usd.discounts());
        eq("3", may1usd.tax());
        eq("0.5", may1usd.deliveryFees());
        eq("0.01", may1usd.rounding());
        eq("18.51", may1usd.netSales());
        eq("9.255", may1usd.averageOrderValue());

        SalesSummaryRow may2usd = row(day.rows(), "2026-05-02", "USD"); // the boundary order landed here
        assertEquals(1, may2usd.paidOrderCount());
        eq("4", may2usd.netSales());

        eq("20000", row(day.rows(), "2026-05-01", "KHR").netSales());
        assertFalse(day.truncated());

        // MONTH grouping aggregates both USD days.
        SalesSummaryReport month = service.salesSummary(admin, storeA, MAY1, MAY3, "MONTH", null);
        SalesSummaryRow mayUsd = row(month.rows(), "2026-05", "USD");
        assertEquals(5, mayUsd.orderCount());
        assertEquals(3, mayUsd.paidOrderCount());
        eq("20", mayUsd.grossSales());
        eq("22.51", mayUsd.netSales());

        // WEEK grouping returns ISO-week labelled periods.
        SalesSummaryReport week = service.salesSummary(admin, storeA, MAY1, MAY3, "WEEK", null);
        assertTrue(week.rows().stream().allMatch(r -> r.period().matches("\\d{4}-W\\d{2}")));

        // Single-currency filter; other currencies disappear entirely.
        SalesSummaryReport usdOnly = service.salesSummary(admin, storeA, MAY1, MAY3, "MONTH", "usd");
        assertTrue(usdOnly.rows().stream().allMatch(r -> "USD".equals(r.currencyCode())));

        // Empty window is a clean empty report.
        assertTrue(service.salesSummary(admin, storeA, Instant.parse("2020-01-01T00:00:00Z"),
                Instant.parse("2020-01-02T00:00:00Z"), "DAY", null).rows().isEmpty());

        SecurityContextHolder.clearContext();
    }

    @Test
    void reportGuardsRangeCurrencyAndStoreScope() {
        seedGlobalAdmin();
        CurrentActor admin = actors.require();
        UUID storeA = newStore(admin);
        UUID storeB = newStore(admin);
        insertOrder("RPT-B1", storeB, "COMPLETED", "PAID", "USD", "1000", "0", "0", "0", "0", "1000",
                "2026-05-01T02:00:00Z");

        // Mandatory bounded range.
        assertEquals("MISSING_DATE_RANGE", assertThrows(ValidationException.class,
                () -> service.salesSummary(admin, storeA, null, MAY3, "DAY", null)).code());
        assertEquals("INVALID_DATE_RANGE", assertThrows(ValidationException.class,
                () -> service.salesSummary(admin, storeA, MAY3, MAY1, "DAY", null)).code());
        assertEquals("INVALID_GROUPING", assertThrows(ValidationException.class,
                () -> service.salesSummary(admin, storeA, MAY1, MAY3, "HOUR", null)).code());
        assertEquals("UNSUPPORTED_CURRENCY", assertThrows(ValidationException.class,
                () -> service.salesSummary(admin, storeA, MAY1, MAY3, "DAY", "EUR")).code());

        // Store manager of storeA: denied another store explicitly...
        UUID mgr = seedUser("rptmgr-" + UUID.randomUUID() + "@smart.coffee");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, assigned_at) "
                + "values (?,?, ?::uuid, ?::uuid, now())", UUID.randomUUID(), mgr, roleIdByCode("STORE_MANAGER"),
                storeA.toString());
        authenticate(mgr);
        CurrentActor manager = actors.require();
        ForbiddenException denied = assertThrows(ForbiddenException.class,
                () -> service.salesSummary(manager, storeB, MAY1, MAY3, "DAY", null));
        assertEquals("STORE_ACCESS_DENIED", denied.code());

        // ...and silently bounded to storeA when no filter is given (the 1000 is absent).
        SalesSummaryReport scoped = service.salesSummary(manager, null, MAY1, MAY3, "MONTH", null);
        assertTrue(scoped.rows().stream().noneMatch(r -> r.netSales().compareTo(new BigDecimal("1000")) >= 0));

        // COGS endpoint shares the same scope guard.
        assertThrows(ForbiddenException.class, () -> service.inventoryConsumption(manager, storeB, MAY1, MAY3));

        SecurityContextHolder.clearContext();
    }

    @Test
    void inventoryConsumptionIsQuantityOnlyWithExplicitCogsGap() {
        seedGlobalAdmin();
        CurrentActor admin = actors.require();
        UUID storeA = newStore(admin);

        UUID unit = UUID.randomUUID();
        jdbc.update("insert into inventory.units_of_measure (unit_id, unit_code, unit_name, unit_type, decimal_scale, "
                + "conversion_factor_to_base, base_unit) values (?::uuid, 'RPTG', 'gram', 'MASS', 3, 1, true)",
                unit.toString());
        UUID ingredient = UUID.randomUUID();
        jdbc.update("insert into inventory.ingredients (ingredient_id, ingredient_code, ingredient_name, base_unit_id, "
                + "standard_cost) values (?::uuid, ?, 'Report Coffee Beans', ?::uuid, 0)", ingredient.toString(),
                "RPTBEAN" + unit.toString().substring(0, 6), unit.toString());
        UUID category = UUID.randomUUID();
        jdbc.update("insert into catalog.product_categories (category_id, category_code, category_name) values "
                + "(?::uuid, ?, 'Report Category')", category.toString(), "RPTC" + category.toString().substring(0, 8));
        UUID product = UUID.randomUUID();
        jdbc.update("insert into catalog.products (product_id, product_code, category_id, product_name, base_price, "
                + "currency_code) values (?::uuid, ?, ?::uuid, 'Report Latte', 5, 'USD')", product.toString(),
                "RPTP" + product.toString().substring(0, 8), category.toString());
        UUID recipe = UUID.randomUUID();
        jdbc.update("insert into recipe.recipe_versions (recipe_version_id, product_id, version_number, status, "
                + "effective_from) values (?::uuid, ?::uuid, 1, 'ACTIVE', now())", recipe.toString(),
                product.toString());
        // 0.02 per unit + 10% wastage → 2 sold = 2 * 0.02 * 1.1 = 0.044
        jdbc.update("insert into recipe.recipe_version_lines (recipe_version_line_id, recipe_version_id, "
                + "ingredient_id, quantity_required_base, wastage_percent) values (?::uuid, ?::uuid, ?::uuid, 0.02, 10)",
                UUID.randomUUID().toString(), recipe.toString(), ingredient.toString());

        UUID paid = insertOrder("RPT-C1", storeA, "COMPLETED", "PAID", "USD", "5", "0", "0", "0", "0", "5",
                "2026-05-01T02:00:00Z");
        insertOrderItem(paid, product, recipe, "Report Latte", 2);
        // A paid item without a recipe snapshot must be disclosed, not guessed.
        insertOrderItem(paid, null, null, "Mystery Item", 1);

        InventoryConsumptionReport report = service.inventoryConsumption(admin, storeA, MAY1, MAY3);
        assertEquals(1, report.rows().size());
        assertEquals("Report Latte", report.rows().get(0).productName());
        assertEquals("Report Coffee Beans", report.rows().get(0).ingredientName());
        assertEquals("RPTG", report.rows().get(0).unitCode());
        eq("0.044", report.rows().get(0).unitsConsumed());
        assertEquals(1, report.itemsWithoutRecipeSnapshot());

        // The documented BACKEND API GAP: no fabricated cost or margin.
        assertFalse(report.cogsAvailable());
        assertFalse(report.grossMarginAvailable());
        assertTrue(report.unavailableReason().contains("BACKEND API GAP"));

        SecurityContextHolder.clearContext();
    }

    @Test
    void reportPermissionsAreSeededPerRole() {
        seedGlobalAdmin();
        CurrentActor admin = actors.require();
        assertTrue(admin.hasPermission(PermissionCodes.REPORT_SALES_READ));
        assertTrue(admin.hasPermission(PermissionCodes.REPORT_INVENTORY_COGS_READ));

        UUID store = newStore(admin);

        UUID mgr = seedUser("rptmgr2-" + UUID.randomUUID() + "@smart.coffee");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, assigned_at) "
                + "values (?,?, ?::uuid, ?::uuid, now())", UUID.randomUUID(), mgr, roleIdByCode("STORE_MANAGER"),
                store.toString());
        authenticate(mgr);
        CurrentActor manager = actors.require();
        assertTrue(manager.hasPermission(PermissionCodes.REPORT_SALES_READ), "V027 grants managers sales reports");
        assertFalse(manager.hasPermission(PermissionCodes.REPORT_INVENTORY_COGS_READ));

        UUID barista = seedUser("rptbar-" + UUID.randomUUID() + "@smart.coffee");
        jdbc.update("insert into identity.user_role_assignments (assignment_id, user_id, role_id, store_id, assigned_at) "
                + "values (?,?, ?::uuid, ?::uuid, now())", UUID.randomUUID(), barista, roleIdByCode("BARISTA"),
                store.toString());
        authenticate(barista);
        CurrentActor worker = actors.require();
        assertFalse(worker.hasPermission(PermissionCodes.REPORT_SALES_READ));
        assertFalse(worker.hasPermission(PermissionCodes.REPORT_INVENTORY_COGS_READ));

        SecurityContextHolder.clearContext();
    }
}
