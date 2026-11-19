package com.smartcoffee.modules.catalog.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.catalog.application.CategoryManagementQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Protected category-management reads for the Dashboard catalog editor
 * ({@code /api/v1/management/catalog/categories}). Returns every category (any
 * active state) ordered for editing, exposing the raw {@code imageObjectKey} and
 * optimistic-lock {@code version}. Guarded by {@code category:manage}; separate
 * from the public active-category listing.
 */
@Tag(name = "Catalog management", description = "Protected category management reads")
@RestController
@RequestMapping("/api/v1/management/catalog/categories")
public class CategoryManagementController {

    private final CategoryManagementQueryService service;
    private final CurrentActorProvider actors;

    public CategoryManagementController(CategoryManagementQueryService service, CurrentActorProvider actors) {
        this.service = service;
        this.actors = actors;
    }

    @Operation(summary = "List categories for management",
            description = "All categories (active or not) ordered by display order/name, each with imageUrl, "
                    + "imageObjectKey and version. Requires category:manage.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Ok"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthenticated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing category:manage")
    })
    @GetMapping
    public ApiResponse<List<ManagementCategoryResponse>> list() {
        var actor = actors.require();
        return ApiResponse.ok(service.list(actor), CorrelationContext.requestId());
    }

    @Operation(summary = "Get one category for management",
            description = "Single category row including the raw imageObjectKey and version. Requires category:manage.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Ok"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthenticated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing category:manage"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "CATEGORY_NOT_FOUND")
    })
    @GetMapping("/{categoryId}")
    public ApiResponse<ManagementCategoryResponse> get(@PathVariable UUID categoryId) {
        var actor = actors.require();
        return ApiResponse.ok(service.get(actor, categoryId), CorrelationContext.requestId());
    }
}
