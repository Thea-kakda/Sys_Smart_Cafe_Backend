package com.smartcoffee.modules.inventory.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.api.PaginatedResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.*;
import com.smartcoffee.modules.inventory.application.GoodsReceiptService;
import com.smartcoffee.modules.inventory.application.PurchaseOrderCommandService;
import com.smartcoffee.modules.inventory.application.PurchaseOrderQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * Purchase-order lifecycle and receiving REST surface, scoped to a store. Thin
 * controllers: authenticate, enforce the permission and the store scope, and
 * delegate. Lifecycle commands require {@code purchase:manage}; the receive
 * action requires {@code inventory:receive} (the physical stock-in). Both are
 * always paired with the store scope.
 */
@Tag(name = "Inventory — Purchase Orders", description = "Store purchase orders and goods receiving")
@RestController
@RequestMapping("/api/v1/stores/{storeId}/purchase-orders")
public class PurchaseOrderController {

    private final PurchaseOrderCommandService commands;
    private final PurchaseOrderQueryService queries;
    private final GoodsReceiptService receiving;
    private final CurrentActorProvider actor;
    private final PermissionEvaluator permissions;
    private final StoreAuthorizationService stores;

    public PurchaseOrderController(PurchaseOrderCommandService commands, PurchaseOrderQueryService queries,
            GoodsReceiptService receiving, CurrentActorProvider actor, PermissionEvaluator permissions,
            StoreAuthorizationService stores) {
        this.commands = commands;
        this.queries = queries;
        this.receiving = receiving;
        this.actor = actor;
        this.permissions = permissions;
        this.stores = stores;
    }

    @Operation(summary = "Create a DRAFT purchase order",
            description = "Validates the supplier + ingredient lines (qty > 0, cost >= 0, no duplicate ingredient). "
                    + "Permission: purchase:manage + store scope.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN / STORE_ACCESS_DENIED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "SUPPLIER_NOT_FOUND / INGREDIENT_NOT_FOUND")
    })
    @PostMapping
    public ApiResponse<PurchaseOrderResponse> create(@PathVariable UUID storeId,
            @Valid @RequestBody CreatePurchaseOrderRequest request) {
        var x = actor.require();
        permissions.require(x, PermissionCodes.PURCHASE_MANAGE);
        stores.requireStore(x, storeId);
        return ApiResponse.ok(commands.create(x, storeId, request), CorrelationContext.requestId());
    }

    @Operation(summary = "List purchase orders for a store",
            description = "Paginated, newest-first, optional status filter. Permission: purchase:manage + store scope.")
    @GetMapping
    public ApiResponse<PaginatedResponse<PurchaseOrderResponse>> list(@PathVariable UUID storeId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var x = actor.require();
        permissions.require(x, PermissionCodes.PURCHASE_MANAGE);
        stores.requireStore(x, storeId);
        var result = queries.list(x, storeId, status, page, size);
        var meta = new PaginatedResponse.Pagination(result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), result.isFirst(), result.isLast());
        return ApiResponse.ok(new PaginatedResponse<>(result.getContent(), meta), CorrelationContext.requestId());
    }

    @Operation(summary = "Get one purchase order", description = "Permission: purchase:manage + store scope.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "PURCHASE_ORDER_NOT_FOUND")
    })
    @GetMapping("/{poId}")
    public ApiResponse<PurchaseOrderResponse> get(@PathVariable UUID storeId, @PathVariable UUID poId) {
        var x = actor.require();
        permissions.require(x, PermissionCodes.PURCHASE_MANAGE);
        stores.requireStore(x, storeId);
        return ApiResponse.ok(queries.get(x, storeId, poId), CorrelationContext.requestId());
    }

    @Operation(summary = "Submit a purchase order",
            description = "DRAFT -> SUBMITTED. Illegal transition -> 409 INVALID_STATUS_TRANSITION. "
                    + "Permission: purchase:manage + store scope.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "INVALID_STATUS_TRANSITION")
    })
    @PostMapping("/{poId}/submit")
    public ApiResponse<PurchaseOrderResponse> submit(@PathVariable UUID storeId, @PathVariable UUID poId) {
        var x = actor.require();
        permissions.require(x, PermissionCodes.PURCHASE_MANAGE);
        stores.requireStore(x, storeId);
        return ApiResponse.ok(commands.submit(x, storeId, poId), CorrelationContext.requestId());
    }

    @Operation(summary = "Cancel a purchase order",
            description = "DRAFT/SUBMITTED (pre-receipt) -> CANCELLED. Permission: purchase:manage + store scope.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "INVALID_STATUS_TRANSITION")
    })
    @PostMapping("/{poId}/cancel")
    public ApiResponse<PurchaseOrderResponse> cancel(@PathVariable UUID storeId, @PathVariable UUID poId,
            @RequestParam(required = false) String reason) {
        var x = actor.require();
        permissions.require(x, PermissionCodes.PURCHASE_MANAGE);
        stores.requireStore(x, storeId);
        return ApiResponse.ok(commands.cancel(x, storeId, poId, reason), CorrelationContext.requestId());
    }

    @Operation(summary = "Close a received purchase order",
            description = "RECEIVED -> CLOSED. Permission: purchase:manage + store scope.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "INVALID_STATUS_TRANSITION")
    })
    @PostMapping("/{poId}/close")
    public ApiResponse<PurchaseOrderResponse> close(@PathVariable UUID storeId, @PathVariable UUID poId) {
        var x = actor.require();
        permissions.require(x, PermissionCodes.PURCHASE_MANAGE);
        stores.requireStore(x, storeId);
        return ApiResponse.ok(commands.close(x, storeId, poId), CorrelationContext.requestId());
    }

    @Operation(summary = "Receive goods against a purchase order",
            description = "Records a goods receipt: increments balances via guarded native mutations and writes "
                    + "one PURCHASE_RECEIPT ledger transaction with real before/after. Partial receipts supported; "
                    + "over-receipt -> 409 OVER_RECEIPT; non-receivable status -> 409 PO_NOT_RECEIVABLE. "
                    + "Permission: inventory:receive + store scope.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN / STORE_ACCESS_DENIED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "PURCHASE_ORDER_NOT_FOUND / INGREDIENT_NOT_FOUND"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "OVER_RECEIPT / PO_NOT_RECEIVABLE / STORE_MISMATCH")
    })
    @PostMapping("/{poId}/receipts")
    public ApiResponse<GoodsReceiptResponse> receive(@PathVariable UUID storeId, @PathVariable UUID poId,
            @Valid @RequestBody ReceiveGoodsRequest request) {
        var x = actor.require();
        permissions.require(x, PermissionCodes.INVENTORY_RECEIVE);
        stores.requireStore(x, storeId);
        return ApiResponse.ok(receiving.receive(x, storeId, poId, request), CorrelationContext.requestId());
    }
}
