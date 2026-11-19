package com.smartcoffee.modules.recipe.application;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.modules.catalog.persistence.ProductRepository;
import com.smartcoffee.modules.catalog.persistence.ProductVariantRepository;
import com.smartcoffee.modules.recipe.api.RecipeVersionResponse;
import com.smartcoffee.modules.recipe.api.request.CreateRecipeDraftRequest;
import com.smartcoffee.modules.recipe.api.request.RecipeItemRequest;
import com.smartcoffee.modules.recipe.api.request.ReplaceRecipeItemsRequest;
import com.smartcoffee.modules.recipe.api.request.UpdateRecipeDraftRequest;
import com.smartcoffee.modules.recipe.domain.RecipeStatus;
import com.smartcoffee.modules.recipe.domain.RecipeVersion;
import com.smartcoffee.modules.recipe.domain.RecipeVersionLine;
import com.smartcoffee.modules.recipe.persistence.RecipeVersionRepository;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Write path for the product-scoped recipe aggregate. Everything is
 * DRAFT-mutable / non-DRAFT-immutable, guarded by optimistic locking.
 * Slice 2B.1 semantics: activation PUBLISHES a version (DRAFT -&gt; ACTIVE,
 * immutable and assignable) without superseding anything; multiple ACTIVE
 * versions may coexist per product. Current selection lives in explicit
 * assignment pointers ({@link RecipeAssignmentService}), never in status.
 * A version that is still assigned (product fallback or variant override)
 * cannot be archived (RECIPE_VERSION_IN_USE). Permissions are enforced
 * service-side.
 */
@Service
public class RecipeCommandService {

    private final RecipeVersionRepository recipes;
    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final PermissionEvaluator permissions;
    private final RecipeItemValidator itemValidator;
    private final RecipeMapper mapper;

    public RecipeCommandService(RecipeVersionRepository recipes, ProductRepository products,
            ProductVariantRepository variants, PermissionEvaluator permissions,
            RecipeItemValidator itemValidator, RecipeMapper mapper) {
        this.recipes = recipes;
        this.products = products;
        this.variants = variants;
        this.permissions = permissions;
        this.itemValidator = itemValidator;
        this.mapper = mapper;
    }

    @Transactional
    public RecipeVersionResponse createDraft(CurrentActor actor, UUID productId, CreateRecipeDraftRequest req) {
        permissions.require(actor, PermissionCodes.RECIPE_MANAGE);
        requireProduct(productId);

        int next = recipes.maxVersionNumber(productId) + 1;
        RecipeVersion v = RecipeVersion.createDraft(productId, next, req.yieldQuantity(),
                req.yieldUnitId(), req.notes(), actor.userId());
        List<RecipeItemRequest> items = req.items() == null ? List.of() : req.items();
        if (!items.isEmpty()) {
            v.replaceLines(itemValidator.build(items));
        }
        try {
            recipes.saveAndFlush(v);
        } catch (DataIntegrityViolationException race) {
            // Concurrent draft creation reserved the same version number.
            throw new ConflictException("RECIPE_VERSION_CONFLICT",
                    "A recipe version was created concurrently for this product. Retry.");
        }
        return mapper.toResponse(v);
    }

    @Transactional
    public RecipeVersionResponse updateDraft(CurrentActor actor, UUID recipeVersionId, UpdateRecipeDraftRequest req) {
        permissions.require(actor, PermissionCodes.RECIPE_MANAGE);
        RecipeVersion v = requireWithLines(recipeVersionId);
        requireEditable(v);
        requireVersion(v, req.expectedVersion());
        v.updateMeta(req.yieldQuantity(), req.yieldUnitId(), req.notes(), actor.userId());
        return persist(v);
    }

    @Transactional
    public RecipeVersionResponse replaceItems(CurrentActor actor, UUID recipeVersionId, ReplaceRecipeItemsRequest req) {
        permissions.require(actor, PermissionCodes.RECIPE_MANAGE);
        RecipeVersion v = requireWithLines(recipeVersionId);
        requireEditable(v);
        requireVersion(v, req.expectedVersion());
        if (req.items() == null || req.items().isEmpty()) {
            throw new ValidationException("RECIPE_ITEM_INVALID", "A recipe version must define at least one line");
        }
        List<RecipeVersionLine> lines = itemValidator.build(req.items());
        v.replaceLines(lines);
        return persist(v);
    }

    /**
     * DRAFT -> ACTIVE (published, immutable, assignable). Slice 2B.1: activation
     * NEVER supersedes or archives another version — multiple assignable
     * versions may coexist per product, and explicit pointers (product
     * fallback / variant override) decide which one is used. Publishing is
     * therefore side-effect-free for current resolution; only assignment
     * changes behaviour. Optimistic locking guards the draft itself.
     */
    @Transactional
    public RecipeVersionResponse activate(CurrentActor actor, UUID recipeVersionId, Long expectedVersion) {
        permissions.require(actor, PermissionCodes.RECIPE_ACTIVATE);
        RecipeVersion target = requireWithLines(recipeVersionId);
        if (target.status() != RecipeStatus.DRAFT) {
            throw new ConflictException("RECIPE_VERSION_IMMUTABLE", "Only a DRAFT recipe version can be activated");
        }
        requireVersion(target, expectedVersion);
        if (target.lines().isEmpty()) {
            throw new ValidationException("RECIPE_VERSION_INVALID", "A recipe version with no lines cannot be activated");
        }

        target.markActive(actor.userId(), Instant.now());
        try {
            recipes.saveAndFlush(target);
        } catch (ObjectOptimisticLockingFailureException stale) {
            throw new ConflictException("RECIPE_VERSION_CONFLICT",
                    "This recipe version was updated by another user. Refresh before retrying.");
        }
        return mapper.toResponse(target);
    }

    /**
     * Archives a version. A version may only be archived when it is NOT
     * currently assigned as some product's fallback or some variant's override
     * (RECIPE_VERSION_IN_USE); assignment is never silently cleared here.
     */
    @Transactional
    public RecipeVersionResponse archive(CurrentActor actor, UUID recipeVersionId, Long expectedVersion) {
        permissions.require(actor, PermissionCodes.RECIPE_ACTIVATE);
        RecipeVersion v = requireWithLines(recipeVersionId);
        requireVersion(v, expectedVersion);
        if (v.status() == RecipeStatus.ARCHIVED) {
            throw new ConflictException("RECIPE_VERSION_IMMUTABLE", "This recipe version is already archived");
        }
        if (products.existsByActiveRecipeVersionId(v.id()) || !variants.findAssignedToRecipe(v.id()).isEmpty()) {
            throw new ConflictException("RECIPE_VERSION_IN_USE",
                    "This recipe version is still assigned as a product fallback or variant override; "
                            + "reassign or clear those pointers before archiving.");
        }
        v.markArchived();
        return persist(v);
    }

    // ------------------------------------------------------------- helpers

    private RecipeVersionResponse persist(RecipeVersion v) {
        try {
            recipes.saveAndFlush(v);
        } catch (ObjectOptimisticLockingFailureException stale) {
            throw new ConflictException("RECIPE_VERSION_CONFLICT",
                    "This recipe version was updated by another user. Refresh before editing again.");
        }
        return mapper.toResponse(v);
    }

    private void requireProduct(UUID productId) {
        if (!products.existsById(productId)) {
            throw new ResourceNotFoundException("PRODUCT_NOT_FOUND", "Product not found");
        }
    }

    private RecipeVersion requireWithLines(UUID recipeVersionId) {
        return recipes.findWithLinesById(recipeVersionId)
                .orElseThrow(() -> new ResourceNotFoundException("RECIPE_VERSION_NOT_FOUND",
                        "Recipe version not found"));
    }

    private void requireEditable(RecipeVersion v) {
        if (!v.isEditable()) {
            throw new ConflictException("RECIPE_VERSION_IMMUTABLE",
                    "Only a DRAFT recipe version can be edited");
        }
    }

    private void requireVersion(RecipeVersion v, Long expectedVersion) {
        if (expectedVersion == null || expectedVersion != v.version()) {
            throw new ConflictException("RECIPE_VERSION_CONFLICT",
                    "This recipe version was updated by another user. Refresh before editing again.");
        }
    }
}
