package com.smartcoffee.modules.inventory.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.api.PaginatedResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.inventory.application.SupplierCommandService;
import com.smartcoffee.modules.inventory.application.SupplierQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * Supplier master REST surface. Thin: authenticate, resolve the actor and
 * delegate. All endpoints require {@code supplier:manage}. Reads return the
 * optimistic-lock {@code version} so the Dashboard can chain PATCH calls.
 */
@Tag(name = "Inventory — Suppliers", description = "Supplier master data for purchasing")
@RestController
@RequestMapping("/api/v1/suppliers")
public class SupplierController {

    private final SupplierQueryService queries;
    private final SupplierCommandService commands;
    private final CurrentActorProvider actor;

    public SupplierController(SupplierQueryService queries, SupplierCommandService commands,
            CurrentActorProvider actor) {
        this.queries = queries;
        this.commands = commands;
        this.actor = actor;
    }

    @Operation(summary = "List/search suppliers",
            description = "Paginated supplier search over code/name (blank query lists all). Page size bounded "
                    + "at 100. Permission: supplier:manage.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "AUTH_UNAUTHORIZED"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — lacks supplier:manage")
    })
    @GetMapping
    public ApiResponse<PaginatedResponse<SupplierResponse>> list(@RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var x = actor.require();
        var result = queries.search(x, q, page, size);
        var meta = new PaginatedResponse.Pagination(result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), result.isFirst(), result.isLast());
        return ApiResponse.ok(new PaginatedResponse<>(result.getContent(), meta), CorrelationContext.requestId());
    }

    @Operation(summary = "Get one supplier", description = "Permission: supplier:manage.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "SUPPLIER_NOT_FOUND")
    })
    @GetMapping("/{supplierId}")
    public ApiResponse<SupplierResponse> get(@PathVariable UUID supplierId) {
        var x = actor.require();
        return ApiResponse.ok(queries.get(x, supplierId), CorrelationContext.requestId());
    }

    @Operation(summary = "Create a supplier",
            description = "Creates a supplier master record. Code uniqueness -> 409 DUPLICATE_CODE. "
                    + "Permission: supplier:manage.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — lacks supplier:manage"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "DUPLICATE_CODE")
    })
    @PostMapping
    public ApiResponse<SupplierResponse> create(@Valid @RequestBody CreateSupplierRequest request) {
        var x = actor.require();
        return ApiResponse.ok(commands.create(x, request), CorrelationContext.requestId());
    }

    @Operation(summary = "Update a supplier",
            description = "Full-replacement update. Stale version -> 409 VERSION_CONFLICT; code clash -> 409 "
                    + "DUPLICATE_CODE; unknown -> 404 SUPPLIER_NOT_FOUND. Permission: supplier:manage.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — lacks supplier:manage"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "SUPPLIER_NOT_FOUND"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "VERSION_CONFLICT / DUPLICATE_CODE")
    })
    @PatchMapping("/{supplierId}")
    public ApiResponse<SupplierResponse> update(@PathVariable UUID supplierId,
            @Valid @RequestBody UpdateSupplierRequest request) {
        var x = actor.require();
        return ApiResponse.ok(commands.update(x, supplierId, request), CorrelationContext.requestId());
    }
}
