package com.smartcoffee.modules.catalog.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.api.PaginatedResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.catalog.application.ProductManagementQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Protected product-management reads for the Dashboard catalog editor
 * ({@code /api/v1/management/catalog/products}). Lives under
 * {@code /api/v1/management} so it is never swallowed by the public GET-only
 * matcher on {@code /api/v1/products/**}. Responses expose the raw
 * {@code imageObjectKey} and optimistic-lock {@code version} the public DTOs
 * omit; guarded by {@code product:manage}.
 */
@Tag(name = "Catalog management", description = "Protected product management reads")
@RestController
@RequestMapping("/api/v1/management/catalog/products")
public class ProductManagementController {

    private final ProductManagementQueryService service;
    private final CurrentActorProvider actors;

    public ProductManagementController(ProductManagementQueryService service, CurrentActorProvider actors) {
        this.service = service;
        this.actors = actors;
    }

    @Operation(summary = "List products for management",
            description = "Paginated master-data listing (any active state) with search + optional category "
                    + "filter. Requires product:manage. Each row carries imageUrl, imageObjectKey and version.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Ok"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthenticated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing product:manage")
    })
    @GetMapping
    public ApiResponse<PaginatedResponse<ManagementProductResponse>> list(
            @RequestParam(defaultValue = "") String search,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "name") String sort,
            @RequestParam(defaultValue = "asc") String direction,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var actor = actors.require();
        var result = service.list(actor, search, categoryId, sort, direction, page, size);
        var meta = new PaginatedResponse.Pagination(result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), result.isFirst(), result.isLast());
        return ApiResponse.ok(new PaginatedResponse<>(result.getContent(), meta), CorrelationContext.requestId());
    }

    @Operation(summary = "Get one product for management",
            description = "Single master-data row including the raw imageObjectKey and version. Requires product:manage.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Ok"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthenticated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing product:manage"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "PRODUCT_NOT_FOUND")
    })
    @GetMapping("/{productId}")
    public ApiResponse<ManagementProductResponse> get(@PathVariable UUID productId) {
        var actor = actors.require();
        return ApiResponse.ok(service.get(actor, productId), CorrelationContext.requestId());
    }
}
