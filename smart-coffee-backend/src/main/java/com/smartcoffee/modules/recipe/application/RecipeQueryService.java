package com.smartcoffee.modules.recipe.application;

import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.modules.recipe.api.RecipeVersionResponse;
import com.smartcoffee.modules.recipe.api.ResolvedRecipeResponse;
import com.smartcoffee.modules.recipe.application.RecipeVersionResolver.Resolved;
import com.smartcoffee.modules.recipe.domain.RecipeVersion;
import com.smartcoffee.modules.recipe.persistence.RecipeVersionRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read side of the recipe management surface. Guards every call with
 * {@code recipe:view}. Resolution delegates to the shared
 * {@link RecipeVersionResolver} so the reported source (VARIANT / PRODUCT / NONE)
 * matches exactly what checkout would use.
 */
@Service
public class RecipeQueryService {

    private final RecipeVersionRepository recipes;
    private final RecipeVersionResolver resolver;
    private final PermissionEvaluator permissions;
    private final RecipeMapper mapper;

    public RecipeQueryService(RecipeVersionRepository recipes, RecipeVersionResolver resolver,
            PermissionEvaluator permissions, RecipeMapper mapper) {
        this.recipes = recipes;
        this.resolver = resolver;
        this.permissions = permissions;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<RecipeVersionResponse> listForProduct(CurrentActor actor, UUID productId) {
        permissions.require(actor, PermissionCodes.RECIPE_VIEW);
        return recipes.findByProductIdOrderByVersionNumberDesc(productId).stream()
                .map(mapper::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public RecipeVersionResponse getVersion(CurrentActor actor, UUID recipeVersionId) {
        permissions.require(actor, PermissionCodes.RECIPE_VIEW);
        RecipeVersion v = recipes.findWithLinesById(recipeVersionId)
                .orElseThrow(() -> new ResourceNotFoundException("RECIPE_VERSION_NOT_FOUND",
                        "Recipe version not found"));
        return mapper.toResponse(v);
    }

    @Transactional(readOnly = true)
    public ResolvedRecipeResponse resolveProduct(CurrentActor actor, UUID productId) {
        permissions.require(actor, PermissionCodes.RECIPE_VIEW);
        Resolved resolved = resolver.resolveProduct(productId);
        return toResponse(productId, null, resolved);
    }

    @Transactional(readOnly = true)
    public ResolvedRecipeResponse resolveVariant(CurrentActor actor, UUID productId, UUID variantId) {
        permissions.require(actor, PermissionCodes.RECIPE_VIEW);
        Resolved resolved = resolver.resolve(productId, variantId);
        return toResponse(productId, variantId, resolved);
    }

    private ResolvedRecipeResponse toResponse(UUID productId, UUID variantId, Resolved resolved) {
        RecipeVersionResponse rv = resolved.present() ? mapper.toResponse(resolved.recipeVersion()) : null;
        return new ResolvedRecipeResponse(productId, variantId, resolved.source(), rv);
    }
}
