package com.smartcoffee.modules.catalog.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.api.PaginatedResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.modules.catalog.application.StoreProductManagementService;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * Protected branch-product management API (Checkpoints 3A/3B). Lives under
 * {@code /api/v1/management} so it is never swallowed by the public
 * {@code /api/v1/stores/**} permitAll matcher; the public catalog contract is
 * unchanged. All pricing/availability/station values in responses are
 * authoritative backend outputs.
 */
@RestController
@RequestMapping("/api/v1/management/stores/{storeId}/store-products")
public class StoreProductManagementController {

    private final StoreProductManagementService service;
    private final CurrentActorProvider actors;
    private final PermissionEvaluator permissions;

    public StoreProductManagementController(StoreProductManagementService service, CurrentActorProvider actors,
            PermissionEvaluator permissions) {
        this.service = service;
        this.actors = actors;
        this.permissions = permissions;
    }

    @GetMapping
    public ApiResponse<PaginatedResponse<ManagementStoreProductResponse>> list(
            @PathVariable UUID storeId,
            @RequestParam(defaultValue = "") String search,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(required = false) Boolean productActive,
            @RequestParam(required = false) Boolean branchAvailable,
            @RequestParam(required = false) String availability,
            @RequestParam(required = false) String overrideState,
            @RequestParam(required = false) String stationCode,
            @RequestParam(defaultValue = "productName") String sort,
            @RequestParam(defaultValue = "asc") String direction,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.BRANCH_PRODUCT_READ);
        var result = service.list(actor, storeId, search, categoryId, productActive, branchAvailable, availability,
                overrideState, stationCode, sort, direction, page, size);
        var meta = new PaginatedResponse.Pagination(result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), result.isFirst(), result.isLast());
        return ApiResponse.ok(new PaginatedResponse<>(result.getContent(), meta), CorrelationContext.requestId());
    }

    @GetMapping("/{productId}")
    public ApiResponse<ManagementStoreProductResponse> get(@PathVariable UUID storeId, @PathVariable UUID productId) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.BRANCH_PRODUCT_READ);
        return ApiResponse.ok(service.get(actor, storeId, productId), CorrelationContext.requestId());
    }

    /** Add a product to the branch (first configuration row). No version accepted. */
    @PostMapping
    public ApiResponse<ManagementStoreProductResponse> add(@PathVariable UUID storeId,
            @Valid @RequestBody CreateStoreProductRequest request) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.BRANCH_PRODUCT_MANAGE);
        return ApiResponse.ok(service.add(actor, storeId, request), CorrelationContext.requestId());
    }

    /** Full-replacement configuration update; requires current storeProductVersion. */
    @PutMapping("/{productId}/configuration")
    public ApiResponse<ManagementStoreProductResponse> updateConfiguration(@PathVariable UUID storeId,
            @PathVariable UUID productId, @Valid @RequestBody UpdateStoreProductConfigurationRequest request) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.BRANCH_PRODUCT_MANAGE);
        return ApiResponse.ok(service.updateConfiguration(actor, storeId, productId, request),
                CorrelationContext.requestId());
    }
}
