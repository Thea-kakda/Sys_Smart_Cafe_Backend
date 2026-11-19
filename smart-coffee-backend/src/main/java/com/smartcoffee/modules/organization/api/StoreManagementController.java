package com.smartcoffee.modules.organization.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.api.PaginatedResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.modules.organization.application.StoreManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

/**
 * Protected branch management API. Distinct from the public, unauthenticated
 * {@code /api/v1/stores} read contract: it lives under {@code /api/v1/management}
 * (no path collision), inherits authentication from the security filter chain, and
 * enforces exact permissions server-side (branch:read / branch:manage). Frontend
 * display uses the localized term "Branch"; the technical term remains Store.
 */
@Tag(name = "Branch Management", description = "Protected branch (store) settings under /api/v1/management/stores")
@RestController
@RequestMapping("/api/v1/management/stores")
public class StoreManagementController {

    private final StoreManagementService service;
    private final CurrentActorProvider actors;
    private final PermissionEvaluator permissions;

    public StoreManagementController(StoreManagementService service, CurrentActorProvider actors,
            PermissionEvaluator permissions) {
        this.service = service;
        this.actors = actors;
        this.permissions = permissions;
    }

    @Operation(summary = "List branches (administration)",
            description = "Paginated branch directory with settings (timezone, currency, language, active). "
                    + "Authentication: bearer access token. Permission: branch:read. Filters: search (code/name "
                    + "fragment), active boolean. Pagination: zero-based page, bounded size. Any branch:read "
                    + "holder may read the directory (settings are not sensitive; operational data stays "
                    + "store-scoped elsewhere). Response: ApiResponse<PaginatedResponse<ManagementStoreResponse>>.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — caller lacks branch:read")
    })
    @GetMapping
    public ApiResponse<PaginatedResponse<ManagementStoreResponse>> list(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(required = false) Boolean active,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.BRANCH_READ);
        Page<ManagementStoreResponse> result = service.list(search, active, page, size);
        var meta = new PaginatedResponse.Pagination(result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), result.isFirst(), result.isLast());
        return ApiResponse.ok(new PaginatedResponse<>(result.getContent(), meta), CorrelationContext.requestId());
    }

    @Operation(summary = "Read one branch",
            description = "Authentication: bearer access token. Permission: branch:read. "
                    + "Response: ApiResponse<ManagementStoreResponse>.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — caller lacks branch:read"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "STORE_NOT_FOUND — unknown branch id")
    })
    @GetMapping("/{storeId}")
    public ApiResponse<ManagementStoreResponse> get(@PathVariable UUID storeId) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.BRANCH_READ);
        return ApiResponse.ok(service.get(storeId), CorrelationContext.requestId());
    }

    @Operation(summary = "Create a branch",
            description = "Authentication: bearer access token. Permission: branch:manage. Request: "
                    + "CreateStoreRequest (code — unique, format-checked; name; address; phone; IANA timezone; "
                    + "defaultCurrency; defaultLanguage; active). Audited. "
                    + "Response: ApiResponse<ManagementStoreResponse>.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR — INVALID_STORE_CODE, INVALID_STORE_NAME, INVALID_ADDRESS, INVALID_TIMEZONE, UNSUPPORTED_CURRENCY, UNSUPPORTED_LANGUAGE, or INVALID_PHONE"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — caller lacks branch:manage"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "STORE_CODE_TAKEN — branch code already in use")
    })
    @PostMapping
    public ApiResponse<ManagementStoreResponse> create(@Valid @RequestBody CreateStoreRequest request) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.BRANCH_MANAGE);
        return ApiResponse.ok(service.create(actor, request), CorrelationContext.requestId());
    }

    @Operation(summary = "Update a branch",
            description = "Edits branch settings; request must echo the current version (optimistic locking) and "
                    + "re-validates code/name/timezone/currency/language/phone. Authentication: bearer access "
                    + "token. Permission: branch:manage. Audited. Response: ApiResponse<ManagementStoreResponse>.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR — field rules as create"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN lacks branch:manage, or STORE_ACCESS_DENIED — store-scoped manager editing a foreign branch"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "STORE_NOT_FOUND — unknown branch id"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "VERSION_CONFLICT — stale version echo")
    })
    @PutMapping("/{storeId}")
    public ApiResponse<ManagementStoreResponse> update(@PathVariable UUID storeId,
            @Valid @RequestBody UpdateStoreRequest request) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.BRANCH_MANAGE);
        return ApiResponse.ok(service.update(actor, storeId, request), CorrelationContext.requestId());
    }

    @Operation(summary = "Open or close a branch",
            description = "Active/inactive transition with a required version echo (StoreStatusRequest: "
                    + "active + version — the DTO carries no free-text reason). "
                    + "Authentication: bearer access token. Permission: branch:manage. Audited. "
                    + "Response: ApiResponse<ManagementStoreResponse>.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR — active or version missing"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED — no valid access token"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — lacks branch:manage, or STORE_ACCESS_DENIED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "STORE_NOT_FOUND — unknown branch id"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "VERSION_CONFLICT — stale version echo")
    })
    @PostMapping("/{storeId}/status")
    public ApiResponse<ManagementStoreResponse> changeStatus(@PathVariable UUID storeId,
            @Valid @RequestBody StoreStatusRequest request) {
        var actor = actors.require();
        permissions.require(actor, PermissionCodes.BRANCH_MANAGE);
        return ApiResponse.ok(service.changeStatus(actor, storeId, request), CorrelationContext.requestId());
    }
}
