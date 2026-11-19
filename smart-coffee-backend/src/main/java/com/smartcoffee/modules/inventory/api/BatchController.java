package com.smartcoffee.modules.inventory.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.common.security.StoreAuthorizationService;
import com.smartcoffee.modules.inventory.application.BatchQueryService;
import com.smartcoffee.modules.inventory.application.BatchService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * Batch / expiry REST surface (V038, M3), scoped to a store. The list
 * ({@code inventory:read} + store scope) reveals a store's lots FEFO-ordered
 * with optional ingredient / expiring-within-N-days filters; the write-off
 * ({@code inventory:adjust} + store scope) retires one lot as a DAMAGED
 * stock-out. Thin: authenticate, resolve the actor, enforce the coarse gate and
 * delegate — the batch rules and the balance/ledger guards live in the services.
 */
@Tag(name = "Inventory — Batches", description = "Batch/lot expiry tracking and FEFO")
@RestController
@RequestMapping("/api/v1/stores/{storeId}/inventory/batches")
public class BatchController {

    private final BatchQueryService queries;
    private final BatchService batchService;
    private final CurrentActorProvider actor;
    private final PermissionEvaluator permissions;
    private final StoreAuthorizationService stores;

    public BatchController(BatchQueryService queries, BatchService batchService, CurrentActorProvider actor,
            PermissionEvaluator permissions, StoreAuthorizationService stores) {
        this.queries = queries;
        this.batchService = batchService;
        this.actor = actor;
        this.permissions = permissions;
        this.stores = stores;
    }

    @Operation(summary = "List a store's batches / lots",
            description = "FEFO-ordered batch rows with ingredient labels and a days-to-expiry hint. Filters: "
                    + "ingredientId, expiringDays (only ACTIVE lots expiring within N days). Permission: "
                    + "inventory:read + store scope.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN / STORE_ACCESS_DENIED")
    })
    @GetMapping
    public ApiResponse<List<BatchExpiryResponse>> list(@PathVariable UUID storeId,
            @RequestParam(required = false) UUID ingredientId,
            @RequestParam(required = false) Integer expiringDays) {
        var x = actor.require();
        return ApiResponse.ok(queries.batches(x, storeId, ingredientId, expiringDays), CorrelationContext.requestId());
    }

    @Operation(summary = "Write off (expire / waste) a batch",
            description = "Retires one ACTIVE lot, drawing its remaining quantity out of the aggregate balance "
                    + "through the guarded statement and stamping one DAMAGED ledger line with the batch id. "
                    + "Permission: inventory:adjust + store scope.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR — blank reason"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN / STORE_ACCESS_DENIED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "BATCH_NOT_FOUND"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "BATCH_NOT_ACTIVE / STORE_MISMATCH / INSUFFICIENT_STOCK")
    })
    @PostMapping("/{batchId}/write-off")
    public ApiResponse<BatchExpiryResponse> writeOff(@PathVariable UUID storeId, @PathVariable UUID batchId,
            @Valid @RequestBody WriteOffBatchRequest request) {
        var x = actor.require();
        permissions.require(x, PermissionCodes.INVENTORY_ADJUST);
        stores.requireStore(x, storeId);
        return ApiResponse.ok(batchService.writeOff(x.userId(), storeId, batchId, request.reason()),
                CorrelationContext.requestId());
    }
}
