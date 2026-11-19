package com.smartcoffee.modules.production.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.common.security.StoreAuthorizationService;
import com.smartcoffee.modules.production.application.ProductionOrderCommandService;
import com.smartcoffee.modules.production.application.ProductionOrderQueryService;
import com.smartcoffee.modules.production.domain.ProductionOrderStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Management API for the {@code ProductionOrder} aggregate (Slice 5A §22,
 * Phase 5A.1). Endpoint set is deliberately scoped to create / update /
 * plan / start / reads; completion / cancel / close HTTP wiring arrives in
 * Phase 5A.2 alongside the inventory posting pipeline.
 *
 * <p>Every endpoint runs the same authorization triple:
 * <ol>
 *   <li>{@link PermissionEvaluator} checks the caller's permission code.</li>
 *   <li>{@link StoreAuthorizationService#requireStore} verifies the actor is
 *       assigned to the store the order belongs to. For write commands this
 *       is enforced AGAIN inside the service after the aggregate loads, so a
 *       bug that passes a mismatched {@code storeId} on the request body
 *       cannot succeed.</li>
 *   <li>{@code Idempotency-Key} header is required for every mutating
 *       endpoint (spec §14) and is database-backed.</li>
 * </ol>
 *
 * <p>The controller never touches inventory, and never reads a
 * client-supplied {@code productionOrderNumber} — the service generates it
 * from a Postgres sequence (spec §13).
 */
@RestController
@RequestMapping("/api/v1/management/production-orders")
public class ProductionOrderManagementController {

    private final ProductionOrderCommandService commands;
    private final ProductionOrderQueryService queries;
    private final CurrentActorProvider actors;
    private final PermissionEvaluator permissions;
    private final StoreAuthorizationService stores;

    public ProductionOrderManagementController(ProductionOrderCommandService commands,
            ProductionOrderQueryService queries, CurrentActorProvider actors,
            PermissionEvaluator permissions, StoreAuthorizationService stores) {
        this.commands = commands;
        this.queries = queries;
        this.actors = actors;
        this.permissions = permissions;
        this.stores = stores;
    }

    @GetMapping
    public ApiResponse<Page<ProductionOrderResponse>> list(
            @RequestParam UUID storeId,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.PRODUCTION_ORDER_VIEW);
        stores.requireStore(actor, storeId);
        Optional<ProductionOrderStatus> parsed = Optional.ofNullable(status).map(ProductionOrderStatus::valueOf);
        return ApiResponse.ok(queries.list(storeId, parsed, page, size), CorrelationContext.requestId());
    }

    @GetMapping("/{productionOrderId}")
    public ApiResponse<ProductionOrderResponse> get(@PathVariable UUID productionOrderId) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.PRODUCTION_ORDER_VIEW);
        var response = queries.get(productionOrderId);
        stores.requireStore(actor, response.storeId());
        return ApiResponse.ok(response, CorrelationContext.requestId());
    }

    @GetMapping("/{productionOrderId}/materials")
    public ApiResponse<List<ProductionOrderResponse.MaterialLine>> materials(@PathVariable UUID productionOrderId) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.PRODUCTION_ORDER_VIEW);
        var response = queries.get(productionOrderId);
        stores.requireStore(actor, response.storeId());
        return ApiResponse.ok(response.materials(), CorrelationContext.requestId());
    }

    @PostMapping
    public ApiResponse<ProductionOrderResponse> create(
            @RequestHeader("Idempotency-Key") UUID idemKey,
            @RequestBody CreateProductionOrderRequest request) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.PRODUCTION_ORDER_CREATE);
        return ApiResponse.ok(commands.createDraft(actor, idemKey, request), CorrelationContext.requestId());
    }

    @PatchMapping("/{productionOrderId}")
    public ApiResponse<ProductionOrderResponse> update(
            @PathVariable UUID productionOrderId,
            @RequestHeader("Idempotency-Key") UUID idemKey,
            @RequestBody UpdateProductionOrderRequest request) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.PRODUCTION_ORDER_CREATE);
        return ApiResponse.ok(commands.updateDraft(actor, idemKey, productionOrderId, request),
                CorrelationContext.requestId());
    }

    @PostMapping("/{productionOrderId}/plan")
    public ApiResponse<ProductionOrderResponse> plan(
            @PathVariable UUID productionOrderId,
            @RequestHeader("Idempotency-Key") UUID idemKey,
            @RequestBody PlanProductionOrderRequest request) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.PRODUCTION_ORDER_PLAN);
        return ApiResponse.ok(commands.plan(actor, idemKey, productionOrderId, request),
                CorrelationContext.requestId());
    }

    @PostMapping("/{productionOrderId}/start")
    public ApiResponse<ProductionOrderResponse> start(
            @PathVariable UUID productionOrderId,
            @RequestHeader("Idempotency-Key") UUID idemKey,
            @RequestBody StartProductionOrderRequest request) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.PRODUCTION_ORDER_START);
        return ApiResponse.ok(commands.start(actor, idemKey, productionOrderId, request),
                CorrelationContext.requestId());
    }

    @PostMapping("/{productionOrderId}/complete")
    public ApiResponse<ProductionOrderResponse> complete(
            @PathVariable UUID productionOrderId,
            @RequestHeader("Idempotency-Key") UUID idemKey,
            @RequestBody CompleteProductionOrderRequest request) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.PRODUCTION_ORDER_COMPLETE);
        return ApiResponse.ok(commands.complete(actor, idemKey, productionOrderId, request),
                CorrelationContext.requestId());
    }

    @PostMapping("/{productionOrderId}/cancel")
    public ApiResponse<ProductionOrderResponse> cancel(
            @PathVariable UUID productionOrderId,
            @RequestHeader("Idempotency-Key") UUID idemKey,
            @RequestBody CancelProductionOrderRequest request) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.PRODUCTION_ORDER_CANCEL);
        return ApiResponse.ok(commands.cancel(actor, idemKey, productionOrderId, request),
                CorrelationContext.requestId());
    }

    @PostMapping("/{productionOrderId}/close")
    public ApiResponse<ProductionOrderResponse> close(
            @PathVariable UUID productionOrderId,
            @RequestHeader("Idempotency-Key") UUID idemKey,
            @RequestBody CloseProductionOrderRequest request) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.PRODUCTION_ORDER_CLOSE);
        return ApiResponse.ok(commands.close(actor, idemKey, productionOrderId, request),
                CorrelationContext.requestId());
    }
}
