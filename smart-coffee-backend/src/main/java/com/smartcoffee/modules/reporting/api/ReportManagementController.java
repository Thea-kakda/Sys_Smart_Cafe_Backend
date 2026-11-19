package com.smartcoffee.modules.reporting.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.modules.reporting.application.ReportQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.Instant;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * Protected BI report API (Master Prompt §10). Read-only, database-aggregated,
 * store-scoped. sales-summary requires report:sales-read; inventory-cogs
 * (consumption quantities with an explicit COGS/gross-margin gap declaration)
 * requires report:inventory-cogs-read. Date ranges are mandatory — there is no
 * unbounded report.
 */
@Tag(name = "BI Reports", description = "Read-only database-aggregated management reports under /api/v1/management/reports")
@RestController
@RequestMapping("/api/v1/management/reports")
public class ReportManagementController {

    private final ReportQueryService service;
    private final CurrentActorProvider actors;
    private final PermissionEvaluator permissions;

    public ReportManagementController(ReportQueryService service, CurrentActorProvider actors,
            PermissionEvaluator permissions) {
        this.service = service;
        this.actors = actors;
        this.permissions = permissions;
    }

    @Operation(summary = "Sales summary report",
            description = "Database-aggregated sales per period bucket and currency. Authentication: bearer "
                    + "access token. Permission: report:sales-read. Store scope: storeId must be inside the "
                    + "caller's assignment; omitting storeId aggregates over the caller's stores (global "
                    + "administrators → all stores). Filters: from and to (ISO instants, both mandatory — there "
                    + "is no unbounded report), grouping DAY|WEEK|MONTH (default DAY), optional currencyCode "
                    + "USD|KHR; currencies are never summed across. Response: ApiResponse<SalesSummaryReport> "
                    + "with rows, bucketing timezone, truncated flag (row scan bounded at 1000), and the "
                    + "authoritative metricBasis definition text.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR — MISSING_DATE_RANGE, INVALID_DATE_RANGE, INVALID_GROUPING, or UNSUPPORTED_CURRENCY"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — lacks report:sales-read, or STORE_ACCESS_DENIED for a foreign storeId")
    })
    @GetMapping("/sales-summary")
    public ApiResponse<SalesSummaryReport> salesSummary(
            @RequestParam(required = false) UUID storeId,
            @RequestParam Instant from,
            @RequestParam Instant to,
            @RequestParam(defaultValue = "DAY") String grouping,
            @RequestParam(required = false) String currencyCode) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.REPORT_SALES_READ);
        return ApiResponse.ok(service.salesSummary(actor, storeId, from, to, grouping, currencyCode),
                CorrelationContext.requestId());
    }

    @Operation(summary = "Inventory consumption report (quantity-only)",
            description = "Recipe-scaled ingredient consumption quantities per product/ingredient/unit for the "
                    + "window. Authentication: bearer access token. Permission: report:inventory-cogs-read. Store "
                    + "scope rules identical to sales-summary. Response: ApiResponse<InventoryConsumptionReport> "
                    + "with rows, itemsWithoutRecipeSnapshot count, truncated flag (bounded at 1000), and "
                    + "cogsAvailable/grossMarginAvailable = false plus an explicit unavailableReason: historical "
                    + "COGS is a documented BACKEND API GAP (no per-sale cost snapshot exists), so no monetized "
                    + "figure is invented.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR — MISSING_DATE_RANGE or INVALID_DATE_RANGE"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — lacks report:inventory-cogs-read, or STORE_ACCESS_DENIED for a foreign storeId")
    })
    @GetMapping("/inventory-cogs")
    public ApiResponse<InventoryConsumptionReport> inventoryCogs(
            @RequestParam(required = false) UUID storeId,
            @RequestParam Instant from,
            @RequestParam Instant to) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.REPORT_INVENTORY_COGS_READ);
        return ApiResponse.ok(service.inventoryConsumption(actor, storeId, from, to), CorrelationContext.requestId());
    }
}
