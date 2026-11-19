package com.smartcoffee.modules.recipe.application;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.modules.catalog.domain.Product;
import com.smartcoffee.modules.catalog.domain.ProductVariant;
import com.smartcoffee.modules.catalog.domain.StockMode;
import com.smartcoffee.modules.catalog.persistence.ProductRepository;
import com.smartcoffee.modules.catalog.persistence.ProductVariantRepository;
import com.smartcoffee.modules.recipe.api.ProductRecipeResponse;
import com.smartcoffee.modules.recipe.api.VariantRecipeResponse;
import com.smartcoffee.modules.recipe.api.request.AssignProductRecipeRequest;
import com.smartcoffee.modules.recipe.api.request.AssignVariantRecipeRequest;
import com.smartcoffee.modules.recipe.domain.RecipeStatus;
import com.smartcoffee.modules.recipe.domain.RecipeVersion;
import com.smartcoffee.modules.recipe.persistence.RecipeVersionRepository;
import java.util.Objects;
import java.util.UUID;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Explicit recipe assignment pointers (Slice 2B.1). The PRODUCT pointer is the
 * fallback recipe; the VARIANT pointer is an override. Both may point at
 * DIFFERENT assignable (ACTIVE) versions of the same product simultaneously —
 * selection is assignment-driven, never status-driven. Assignments validate
 * version ownership + assignability and use optimistic locking; clearing a
 * variant override restores the product fallback, clearing a product fallback
 * is refused for RECIPE_BASED products. Assigning a version always leaves
 * current resolution rules untouched otherwise (no cascade, no supersede).
 */
@Service
public class RecipeAssignmentService {

    private final ProductVariantRepository variants;
    private final ProductRepository products;
    private final RecipeVersionRepository recipes;
    private final PermissionEvaluator permissions;
    private final RecipeMapper mapper;

    public RecipeAssignmentService(ProductVariantRepository variants, ProductRepository products,
            RecipeVersionRepository recipes, PermissionEvaluator permissions, RecipeMapper mapper) {
        this.variants = variants;
        this.products = products;
        this.recipes = recipes;
        this.permissions = permissions;
        this.mapper = mapper;
    }

    @Transactional
    public ProductRecipeResponse assignProduct(CurrentActor actor, UUID productId,
            AssignProductRecipeRequest req) {
        permissions.require(actor, PermissionCodes.RECIPE_ASSIGN);
        Product product = products.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("PRODUCT_NOT_FOUND", "Product not found"));
        if (req.expectedProductVersion() == null || req.expectedProductVersion() != product.version()) {
            throw new ConflictException("PRODUCT_VERSION_CONFLICT",
                    "This product was updated by another user. Refresh before retrying.");
        }

        if (req.recipeVersionId() == null) {
            // Removal is only legitimate when the product does not depend on a
            // recipe to be producible at all.
            if (product.stockMode() == StockMode.RECIPE_BASED) {
                throw new ValidationException("PRODUCT_RECIPE_REQUIRED",
                        "A RECIPE_BASED product must keep a fallback recipe; assign another version first");
            }
            product.clearRecipeVersion();
        } else {
            RecipeVersion rv = requireAssignableVersion(req.recipeVersionId(), productId,
                    "PRODUCT_RECIPE_MISMATCH");
            product.assignRecipeVersion(rv.id());
        }

        try {
            products.saveAndFlush(product);
        } catch (ObjectOptimisticLockingFailureException stale) {
            throw new ConflictException("PRODUCT_VERSION_CONFLICT",
                    "This product was updated by another user. Refresh before retrying.");
        }
        return mapper.toProductResponse(product);
    }

    @Transactional
    public VariantRecipeResponse assign(CurrentActor actor, UUID variantId, AssignVariantRecipeRequest req) {
        permissions.require(actor, PermissionCodes.RECIPE_ASSIGN);
        ProductVariant variant = requireVariant(variantId);
        if (req.expectedVariantVersion() == null || req.expectedVariantVersion() != variant.version()) {
            throw new ConflictException("VARIANT_VERSION_CONFLICT",
                    "This variant was updated by another user. Refresh before retrying.");
        }

        if (req.recipeVersionId() == null) {
            variant.clearRecipeVersion();
        } else {
            RecipeVersion rv = requireAssignableVersion(req.recipeVersionId(), variant.productId(),
                    "VARIANT_RECIPE_PRODUCT_MISMATCH");
            variant.assignRecipeVersion(rv.id());
        }

        try {
            variants.saveAndFlush(variant);
        } catch (ObjectOptimisticLockingFailureException stale) {
            throw new ConflictException("VARIANT_VERSION_CONFLICT",
                    "This variant was updated by another user. Refresh before retrying.");
        }
        return mapper.toVariantResponse(variant);
    }

    private ProductVariant requireVariant(UUID variantId) {
        return variants.findByIdAndDeletedAtIsNull(variantId)
                .orElseThrow(() -> new ResourceNotFoundException("VARIANT_NOT_FOUND", "Variant not found"));
    }

    /**
     * Shared assignment validation (no duplicated rules between the product and
     * variant paths): the version must exist, be published/assignable (ACTIVE
     * — DRAFT, SUPERSEDED and ARCHIVED are not) and belong to {@code productId}.
     * The ownership-mismatch code stays context-specific for API compatibility.
     */
    private RecipeVersion requireAssignableVersion(UUID recipeVersionId, UUID productId, String mismatchCode) {
        RecipeVersion rv = recipes.findWithLinesById(recipeVersionId)
                .orElseThrow(() -> new ResourceNotFoundException("RECIPE_VERSION_NOT_FOUND",
                        "Recipe version not found"));
        if (rv.status() != RecipeStatus.ACTIVE) {
            throw new ValidationException("RECIPE_VERSION_NOT_ACTIVE",
                    "Only a published (ACTIVE) recipe version can be assigned");
        }
        if (!Objects.equals(rv.productId(), productId)) {
            throw new ValidationException(mismatchCode,
                    "The recipe version belongs to a different product");
        }
        return rv;
    }
}
