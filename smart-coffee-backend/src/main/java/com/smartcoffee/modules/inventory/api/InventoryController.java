package com.smartcoffee.modules.inventory.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.api.PaginatedResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.*;
import com.smartcoffee.modules.inventory.application.InventoryAdjustmentService;
import com.smartcoffee.modules.inventory.application.InventoryQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Inventory", description = "Store balances, manual adjustments, movement ledger")
@RestController
@RequestMapping("/api/v1/stores/{storeId}/inventory")
public class InventoryController {
    private final InventoryQueryService service;
    private final InventoryAdjustmentService adjustments;
    private final CurrentActorProvider actor;
    private final PermissionEvaluator permissions;
    private final StoreAuthorizationService stores;

    public InventoryController(InventoryQueryService s, InventoryAdjustmentService adj, CurrentActorProvider a,
            PermissionEvaluator p, StoreAuthorizationService st) {
        service = s;
        adjustments = adj;
        actor = a;
        permissions = p;
        stores = st;
    }

    @GetMapping
    public ApiResponse<List<InventoryBalanceResponse>> list(@PathVariable UUID storeId) {
        var x = actor.require();
        permissions.require(x, PermissionCodes.INVENTORY_READ);
        stores.requireStore(x, storeId);
        return ApiResponse.ok(service.list(storeId), CorrelationContext.requestId());
    }

    @Operation(summary = "Record a manual stock adjustment",
            description = "Single-ingredient IN/OUT movement with a mandatory reason (Full-stack wiring §1.5). "
                    + "Balances change through guarded conditional updates: OUT can never dip below committed "
                    + "reservations or zero (409 INSUFFICIENT_STOCK). Writes one ADJUSTMENT/STOCK_IN/RETURN/"
                    + "DAMAGED ledger transaction with one line and an audit entry. unitCost restates the "
                    + "moving average on IN when provided. Authentication: bearer access token. Permission: "
                    + "inventory:adjust plus the store scope. Response: ApiResponse<InventoryAdjustmentResponse>.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR — blank reason, bad direction/type, non-positive quantity"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN / STORE_ACCESS_DENIED — lacks inventory:adjust or store outside scope"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "INGREDIENT_NOT_FOUND — unknown ingredient id"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "INSUFFICIENT_STOCK — adjustment would breach reserved/zero floor")
    })
    @PostMapping("/adjustments")
    public ApiResponse<InventoryAdjustmentResponse> adjust(@PathVariable UUID storeId,
            @Valid @RequestBody AdjustInventoryRequest request) {
        var x = actor.require();
        permissions.require(x, PermissionCodes.INVENTORY_ADJUST);
        stores.requireStore(x, storeId);
        return ApiResponse.ok(adjustments.adjust(x, storeId, request), CorrelationContext.requestId());
    }

    @Operation(summary = "Paginated stock movement ledger",
            description = "Newest-first ledger LINEs (one row per ingredient per transaction) joined to "
                    + "ingredient/unit labels; backs the POS and dashboard movement tables (§1.5). Filters: "
                    + "type (STOCK_IN|ADJUSTMENT|SALE_CONSUMPTION|RETURN|DAMAGED), ingredientId. Page size "
                    + "bounded at 100. Authentication: bearer access token. Permission: inventory:read plus "
                    + "the store scope. Response: ApiResponse<PaginatedResponse<StockMovementResponse>>.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR — unknown movement type"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN / STORE_ACCESS_DENIED — lacks inventory:read or store outside scope")
    })
    @GetMapping("/movements")
    public ApiResponse<PaginatedResponse<StockMovementResponse>> movements(@PathVariable UUID storeId,
            @RequestParam(required = false) String type,
            @RequestParam(required = false) UUID ingredientId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var x = actor.require();
        permissions.require(x, PermissionCodes.INVENTORY_READ);
        stores.requireStore(x, storeId);
        var result = adjustments.movements(storeId, type, ingredientId, page, size);
        var meta = new PaginatedResponse.Pagination(result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), result.isFirst(), result.isLast());
        return ApiResponse.ok(new PaginatedResponse<>(result.getContent(), meta), CorrelationContext.requestId());
    }
}

