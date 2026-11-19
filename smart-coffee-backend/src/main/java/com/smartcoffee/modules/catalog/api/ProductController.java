package com.smartcoffee.modules.catalog.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.catalog.application.ProductCommandService;
import com.smartcoffee.modules.catalog.application.StoreCatalogQueryService;
import com.smartcoffee.modules.catalog.api.request.CreateProductRequest;
import com.smartcoffee.modules.catalog.api.request.UpdateProductRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * Product catalog API. The {@code GET} detail stays public (POS/Customer
 * browsing) — see {@code SecurityConfig}, which permits only GET on this path.
 * The {@code POST}/{@code PATCH} master-data writes are authenticated and
 * guarded by {@code product:manage} inside {@link ProductCommandService}; the
 * raw image object key is managed through the separate
 * {@code /api/v1/management/catalog} surface.
 */
@Tag(name = "Products", description = "Catalog product read (public) and master-data write (product:manage)")
@RestController
@RequestMapping("/api/v1/products")
public class ProductController {
    private final StoreCatalogQueryService service;
    private final ProductCommandService commandService;
    private final CurrentActorProvider actors;

    public ProductController(StoreCatalogQueryService service, ProductCommandService commandService,
            CurrentActorProvider actors) {
        this.service = service;
        this.commandService = commandService;
        this.actors = actors;
    }

    @GetMapping("/{productId}")
    public ApiResponse<ProductDetailsResponse> details(@PathVariable UUID productId, @RequestParam UUID storeId) {
        return ApiResponse.ok(service.details(storeId, productId), CorrelationContext.requestId());
    }

    @Operation(summary = "Create a product",
            description = "Creates catalog master data. Requires a bearer token with product:manage. "
                    + "imageObjectKey must be a products/ key returned by POST /api/v1/files/products. "
                    + "Response: ApiResponse<ManagementProductResponse> including the raw objectKey, a "
                    + "short-lived imageUrl and the optimistic-lock version to echo on PATCH.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation / INVALID_OBJECT_KEY"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthenticated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing product:manage"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "DUPLICATE_CODE")
    })
    @PostMapping
    public ApiResponse<ManagementProductResponse> create(@Valid @RequestBody CreateProductRequest request) {
        var actor = actors.require();
        return ApiResponse.ok(commandService.create(actor, request), CorrelationContext.requestId());
    }

    @Operation(summary = "Update a product",
            description = "Full-replacement update guarded by product:manage. Must echo the current "
                    + "version; a stale value returns 409 VERSION_CONFLICT. Changing imageObjectKey swaps "
                    + "the image (the previous object is deleted after commit); null/blank clears it.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation / INVALID_OBJECT_KEY"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthenticated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing product:manage"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "PRODUCT_NOT_FOUND"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "VERSION_CONFLICT / DUPLICATE_CODE")
    })
    @PatchMapping("/{productId}")
    public ApiResponse<ManagementProductResponse> update(@PathVariable UUID productId,
            @Valid @RequestBody UpdateProductRequest request) {
        var actor = actors.require();
        return ApiResponse.ok(commandService.update(actor, productId, request), CorrelationContext.requestId());
    }
}
