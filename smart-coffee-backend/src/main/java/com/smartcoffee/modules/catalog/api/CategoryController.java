package com.smartcoffee.modules.catalog.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.catalog.api.request.CreateCategoryRequest;
import com.smartcoffee.modules.catalog.api.request.UpdateCategoryRequest;
import com.smartcoffee.modules.catalog.application.CategoryCommandService;
import com.smartcoffee.modules.catalog.application.CategoryQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * Category catalog API. The active-category {@code GET} listing stays public for
 * POS/Customer browsing; {@code POST}/{@code PATCH} master-data writes are
 * authenticated and guarded by {@code category:manage} inside
 * {@link CategoryCommandService}. Raw image keys and optimistic-lock versions are
 * read through {@code /api/v1/management/catalog}.
 */
@Tag(name = "Categories", description = "Catalog category read (public) and master-data write (category:manage)")
@RestController
@RequestMapping("/api/v1/categories")
public class CategoryController {
    private final CategoryQueryService service;
    private final CategoryCommandService commandService;
    private final CurrentActorProvider actors;

    public CategoryController(CategoryQueryService service, CategoryCommandService commandService,
            CurrentActorProvider actors) {
        this.service = service;
        this.commandService = commandService;
        this.actors = actors;
    }

    @GetMapping
    public ApiResponse<List<CategoryResponse>> list() {
        return ApiResponse.ok(service.list(), CorrelationContext.requestId());
    }

    @Operation(summary = "Create a category",
            description = "Creates category master data. Requires a bearer token with category:manage. "
                    + "imageObjectKey must be a categories/ key returned by POST /api/v1/files/categories. "
                    + "Response: ApiResponse<ManagementCategoryResponse>.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Created"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation / INVALID_OBJECT_KEY / PARENT_NOT_FOUND"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthenticated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing category:manage"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "DUPLICATE_CODE")
    })
    @PostMapping
    public ApiResponse<ManagementCategoryResponse> create(@Valid @RequestBody CreateCategoryRequest request) {
        var actor = actors.require();
        return ApiResponse.ok(commandService.create(actor, request), CorrelationContext.requestId());
    }

    @Operation(summary = "Update a category",
            description = "Full-replacement update guarded by category:manage. Must echo the current "
                    + "version; stale maps to 409 VERSION_CONFLICT. parentCategoryId null moves the "
                    + "category to top level; a self-parent is rejected.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "200", description = "Updated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "Validation / INVALID_OBJECT_KEY / INVALID_PARENT"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "401", description = "Unauthenticated"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "Missing category:manage"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "CATEGORY_NOT_FOUND"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "VERSION_CONFLICT / DUPLICATE_CODE")
    })
    @PatchMapping("/{categoryId}")
    public ApiResponse<ManagementCategoryResponse> update(@PathVariable UUID categoryId,
            @Valid @RequestBody UpdateCategoryRequest request) {
        var actor = actors.require();
        return ApiResponse.ok(commandService.update(actor, categoryId, request), CorrelationContext.requestId());
    }
}
