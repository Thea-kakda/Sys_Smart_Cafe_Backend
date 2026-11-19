package com.smartcoffee.modules.recipe.api;

import com.smartcoffee.common.api.ApiResponse;
import com.smartcoffee.common.context.CorrelationContext;
import com.smartcoffee.common.security.CurrentActorProvider;
import com.smartcoffee.modules.recipe.api.request.AssignProductRecipeRequest;
import com.smartcoffee.modules.recipe.api.request.AssignVariantRecipeRequest;
import com.smartcoffee.modules.recipe.api.request.CreateRecipeDraftRequest;
import com.smartcoffee.modules.recipe.api.request.ReplaceRecipeItemsRequest;
import com.smartcoffee.modules.recipe.api.request.UpdateRecipeDraftRequest;
import com.smartcoffee.modules.recipe.application.RecipeAssignmentService;
import com.smartcoffee.modules.recipe.application.RecipeCommandService;
import com.smartcoffee.modules.recipe.application.RecipeQueryService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Protected recipe management surface (Slice 2A). Lives under
 * {@code /api/v1/management/catalog} to reuse the authenticated management
 * matcher and stay clear of the public GET-only {@code /api/v1/products/**}.
 * Authorization is enforced per-operation inside the application services
 * (recipe:view / manage / activate / assign).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>POST /products/{productId}/recipes                    create DRAFT</li>
 *   <li>GET  /products/{productId}/recipes                    list versions</li>
 *   <li>GET  /products/{productId}/recipes/resolve            resolved (product) recipe</li>
 *   <li>GET  /products/{productId}/variants/{variantId}/recipe/resolve  resolved (variant override) recipe</li>
 *   <li>GET  /recipes/{recipeVersionId}                       version detail</li>
 *   <li>PATCH /recipes/{recipeVersionId}                      update DRAFT header</li>
 *   <li>PUT  /recipes/{recipeVersionId}/items                 replace DRAFT lines</li>
 *   <li>POST /recipes/{recipeVersionId}/activate              DRAFT -&gt; ACTIVE</li>
 *   <li>POST /recipes/{recipeVersionId}/archive               archive (unassigned only)</li>
 *   <li>PUT  /products/{productId}/recipe                     assign/clear product fallback (2B.1)</li>
 *   <li>PUT  /variants/{variantId}/recipe                     assign/clear variant override</li>
 * </ul>
 */
@Tag(name = "Recipes", description = "Recipe version management and variant assignment")
@RestController
@RequestMapping("/api/v1/management/catalog")
public class RecipeManagementController {

    private final RecipeQueryService queries;
    private final RecipeCommandService commands;
    private final RecipeAssignmentService assignment;
    private final CurrentActorProvider actors;

    public RecipeManagementController(RecipeQueryService queries, RecipeCommandService commands,
            RecipeAssignmentService assignment, CurrentActorProvider actors) {
        this.queries = queries;
        this.commands = commands;
        this.assignment = assignment;
        this.actors = actors;
    }

    @PostMapping("/products/{productId}/recipes")
    public ApiResponse<RecipeVersionResponse> createDraft(@PathVariable UUID productId,
            @Valid @RequestBody CreateRecipeDraftRequest req) {
        var actor = actors.require();
        return ApiResponse.ok(commands.createDraft(actor, productId, req), CorrelationContext.requestId());
    }

    @GetMapping("/products/{productId}/recipes")
    public ApiResponse<List<RecipeVersionResponse>> list(@PathVariable UUID productId) {
        var actor = actors.require();
        return ApiResponse.ok(queries.listForProduct(actor, productId), CorrelationContext.requestId());
    }

    @GetMapping("/products/{productId}/recipes/resolve")
    public ApiResponse<ResolvedRecipeResponse> resolveProduct(@PathVariable UUID productId) {
        var actor = actors.require();
        return ApiResponse.ok(queries.resolveProduct(actor, productId), CorrelationContext.requestId());
    }

    @GetMapping("/products/{productId}/variants/{variantId}/recipe/resolve")
    public ApiResponse<ResolvedRecipeResponse> resolveVariant(@PathVariable UUID productId,
            @PathVariable UUID variantId) {
        var actor = actors.require();
        return ApiResponse.ok(queries.resolveVariant(actor, productId, variantId), CorrelationContext.requestId());
    }

    @GetMapping("/recipes/{recipeVersionId}")
    public ApiResponse<RecipeVersionResponse> getVersion(@PathVariable UUID recipeVersionId) {
        var actor = actors.require();
        return ApiResponse.ok(queries.getVersion(actor, recipeVersionId), CorrelationContext.requestId());
    }

    @PatchMapping("/recipes/{recipeVersionId}")
    public ApiResponse<RecipeVersionResponse> updateDraft(@PathVariable UUID recipeVersionId,
            @Valid @RequestBody UpdateRecipeDraftRequest req) {
        var actor = actors.require();
        return ApiResponse.ok(commands.updateDraft(actor, recipeVersionId, req), CorrelationContext.requestId());
    }

    @PutMapping("/recipes/{recipeVersionId}/items")
    public ApiResponse<RecipeVersionResponse> replaceItems(@PathVariable UUID recipeVersionId,
            @Valid @RequestBody ReplaceRecipeItemsRequest req) {
        var actor = actors.require();
        return ApiResponse.ok(commands.replaceItems(actor, recipeVersionId, req), CorrelationContext.requestId());
    }

    @PostMapping("/recipes/{recipeVersionId}/activate")
    public ApiResponse<RecipeVersionResponse> activate(@PathVariable UUID recipeVersionId,
            @RequestParam @NotNull Long expectedVersion) {
        var actor = actors.require();
        return ApiResponse.ok(commands.activate(actor, recipeVersionId, expectedVersion), CorrelationContext.requestId());
    }

    @PostMapping("/recipes/{recipeVersionId}/archive")
    public ApiResponse<RecipeVersionResponse> archive(@PathVariable UUID recipeVersionId,
            @RequestParam @NotNull Long expectedVersion) {
        var actor = actors.require();
        return ApiResponse.ok(commands.archive(actor, recipeVersionId, expectedVersion), CorrelationContext.requestId());
    }

    @PutMapping("/products/{productId}/recipe")
    public ApiResponse<ProductRecipeResponse> assignProductRecipe(@PathVariable UUID productId,
            @Valid @RequestBody AssignProductRecipeRequest req) {
        var actor = actors.require();
        return ApiResponse.ok(assignment.assignProduct(actor, productId, req), CorrelationContext.requestId());
    }

    @PutMapping("/variants/{variantId}/recipe")
    public ApiResponse<VariantRecipeResponse> assignVariant(@PathVariable UUID variantId,
            @Valid @RequestBody AssignVariantRecipeRequest req) {
        var actor = actors.require();
        return ApiResponse.ok(assignment.assign(actor, variantId, req), CorrelationContext.requestId());
    }
}
