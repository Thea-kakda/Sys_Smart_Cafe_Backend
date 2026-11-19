package com.smartcoffee.modules.inventory.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.api.PaginatedResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.inventory.application.IngredientCommandService;
import com.smartcoffee.modules.inventory.application.IngredientQueryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

/**
 * Ingredient master REST surface (M2), closing the Dashboard's documented
 * ingredient API gap. Thin: authenticate, resolve the actor and delegate.
 * Management reads require {@code inventory:read}; writes require
 * {@code ingredient:manage}. Reads echo the optimistic-lock {@code version} so
 * the Dashboard can chain PATCH calls.
 */
@Tag(name = "Inventory — Ingredients", description = "Ingredient master data")
@RestController
@RequestMapping("/api/v1/ingredients")
public class IngredientController {

    private final IngredientQueryService queries;
    private final IngredientCommandService commands;
    private final CurrentActorProvider actor;

    public IngredientController(IngredientQueryService queries, IngredientCommandService commands,
            CurrentActorProvider actor) {
        this.queries = queries;
        this.commands = commands;
        this.actor = actor;
    }

    @Operation(summary = "List/search ingredients",
            description = "Paginated ingredient search over code/name (blank query lists all). Page size bounded "
                    + "at 100. Permission: inventory:read.")
    @GetMapping
    public ApiResponse<PaginatedResponse<IngredientResponse>> list(@RequestParam(required = false) String q,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        var x = actor.require();
        var result = queries.search(x, q, page, size);
        var meta = new PaginatedResponse.Pagination(result.getNumber(), result.getSize(), result.getTotalElements(),
                result.getTotalPages(), result.isFirst(), result.isLast());
        return ApiResponse.ok(new PaginatedResponse<>(result.getContent(), meta), CorrelationContext.requestId());
    }

    @Operation(summary = "Get one ingredient", description = "Permission: inventory:read.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "INGREDIENT_NOT_FOUND")
    })
    @GetMapping("/{ingredientId}")
    public ApiResponse<IngredientResponse> get(@PathVariable UUID ingredientId) {
        var x = actor.require();
        return ApiResponse.ok(queries.get(x, ingredientId), CorrelationContext.requestId());
    }

    @Operation(summary = "Create an ingredient",
            description = "Creates an ingredient master record. Code uniqueness -> 409 DUPLICATE_CODE; unknown base "
                    + "unit -> 404 UNIT_NOT_FOUND. Permission: ingredient:manage.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "400", description = "VALIDATION_ERROR"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — lacks ingredient:manage"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "DUPLICATE_CODE")
    })
    @PostMapping
    public ApiResponse<IngredientResponse> create(@Valid @RequestBody CreateIngredientRequest request) {
        var x = actor.require();
        return ApiResponse.ok(commands.create(x, request), CorrelationContext.requestId());
    }

    @Operation(summary = "Update an ingredient",
            description = "Full-replacement update. Stale version -> 409 VERSION_CONFLICT; code clash -> 409 "
                    + "DUPLICATE_CODE; unknown -> 404 INGREDIENT_NOT_FOUND. Permission: ingredient:manage.")
    @ApiResponses({
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "403", description = "AUTH_FORBIDDEN — lacks ingredient:manage"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "404", description = "INGREDIENT_NOT_FOUND"),
            @io.swagger.v3.oas.annotations.responses.ApiResponse(responseCode = "409", description = "VERSION_CONFLICT / DUPLICATE_CODE")
    })
    @PatchMapping("/{ingredientId}")
    public ApiResponse<IngredientResponse> update(@PathVariable UUID ingredientId,
            @Valid @RequestBody UpdateIngredientRequest request) {
        var x = actor.require();
        return ApiResponse.ok(commands.update(x, ingredientId, request), CorrelationContext.requestId());
    }
}
