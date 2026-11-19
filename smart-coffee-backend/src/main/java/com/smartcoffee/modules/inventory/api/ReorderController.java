package com.smartcoffee.modules.inventory.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.inventory.application.ReorderActionService;
import com.smartcoffee.modules.inventory.application.ReorderQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * Low-stock / reorder planning REST surface (M2), scoped to a store. The reads
 * ({@code low-stock}, {@code reorder-suggestions}) require {@code inventory:read}
 * plus the store scope; converting suggestions into a DRAFT purchase order
 * requires {@code inventory:reorder}. Thin: authenticate, resolve the actor and
 * delegate.
 */
@Tag(name = "Inventory — Reorder", description = "Low-stock and reorder planning")
@RestController
@RequestMapping("/api/v1/stores/{storeId}/inventory")
public class ReorderController {

    private final ReorderQueryService queries;
    private final ReorderActionService actions;
    private final CurrentActorProvider actor;

    public ReorderController(ReorderQueryService queries, ReorderActionService actions, CurrentActorProvider actor) {
        this.queries = queries;
        this.actions = actions;
        this.actor = actor;
    }

    @Operation(summary = "List low-stock balances for a store",
            description = "Balances whose available stock is at/below the reorder point. Permission: "
                    + "inventory:read + store scope.")
    @GetMapping("/low-stock")
    public ApiResponse<List<LowStockResponse>> lowStock(@PathVariable UUID storeId) {
        var x = actor.require();
        return ApiResponse.ok(queries.lowStock(x, storeId), CorrelationContext.requestId());
    }

    @Operation(summary = "Reorder suggestions for a store",
            description = "Low-stock balances with a suggested replenishment quantity (max_stock - current_stock). "
                    + "Permission: inventory:read + store scope.")
    @GetMapping("/reorder-suggestions")
    public ApiResponse<List<ReorderSuggestionResponse>> reorderSuggestions(@PathVariable UUID storeId) {
        var x = actor.require();
        return ApiResponse.ok(queries.reorderSuggestions(x, storeId), CorrelationContext.requestId());
    }

    @Operation(summary = "Draft a purchase order from reorder lines",
            description = "Creates a DRAFT purchase order for the chosen supplier + lines. No stock/money moves "
                    + "until the PO is submitted and received. Permission: inventory:reorder + store scope.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN / STORE_ACCESS_DENIED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "SUPPLIER_NOT_FOUND / INGREDIENT_NOT_FOUND")
    })
    @PostMapping("/reorder")
    public ApiResponse<PurchaseOrderResponse> reorder(@PathVariable UUID storeId,
            @Valid @RequestBody RequestReorderRequest request) {
        var x = actor.require();
        return ApiResponse.ok(actions.draftPurchaseOrder(x, storeId, request), CorrelationContext.requestId());
    }
}
