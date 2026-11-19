package com.smartcoffee.modules.recipe.application;

import com.smartcoffee.modules.catalog.domain.Product;
import com.smartcoffee.modules.catalog.domain.ProductVariant;
import com.smartcoffee.modules.catalog.persistence.ProductRepository;
import com.smartcoffee.modules.catalog.persistence.ProductVariantRepository;
import com.smartcoffee.modules.recipe.domain.RecipeStatus;
import com.smartcoffee.modules.recipe.domain.RecipeVersion;
import com.smartcoffee.modules.recipe.persistence.RecipeVersionRepository;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single source of truth for "which recipe version governs this sale". Shared by
 * the checkout demand path ({@link RecipeDemandService}), the management
 * resolved-recipe endpoint, and Slice 2B consumption planning — so recipe
 * resolution logic is never duplicated.
 *
 * <p>Resolution is ASSIGNMENT-driven (Slice 2B.1 correction): explicit pointer
 * columns — not status queries — select the governing version. Precedence:
 * <ol>
 *   <li>{@code ProductVariant.activeRecipeVersionId} (override) when the variant
 *     belongs to the product and the pointed version is still assignable
 *     (ACTIVE) and owned by the same product → {@link Source#VARIANT}.</li>
 *   <li>{@code Product.activeRecipeVersionId} (fallback) under the same
 *     validation → {@link Source#PRODUCT}.</li>
 *   <li>Otherwise {@link Source#NONE}. Legacy products without a recipe are
 *     never invalidated, and a dangling pointer (archived / other-product
 *     version) degrades to the next tier instead of mis-selling.</li>
 * </ol>
 *
 * <p>Multiple assignable (ACTIVE) versions may coexist per product: the product
 * and one of its variants may point at two DIFFERENT versions simultaneously.
 * This resolver must never select by "the ACTIVE version" — see V044.
 *
 * <p>The resolver is read-only: it never mutates a {@link RecipeVersion} or its
 * lines. Lines are loaded with an entity graph so the demand loop stays at one
 * query per product (no N+1).
 */
@Service
public class RecipeVersionResolver {

    public enum Source { VARIANT, PRODUCT, NONE }

    /** Immutable resolution outcome: the source that produced it and the version (or null). */
    public record Resolved(Source source, RecipeVersion recipeVersion) {
        public boolean present() {
            return recipeVersion != null;
        }
    }

    private static final Resolved NONE = new Resolved(Source.NONE, null);

    private final RecipeVersionRepository recipes;
    private final ProductVariantRepository variants;
    private final ProductRepository products;

    public RecipeVersionResolver(RecipeVersionRepository recipes, ProductVariantRepository variants,
            ProductRepository products) {
        this.recipes = recipes;
        this.variants = variants;
        this.products = products;
    }

    /**
     * Product-only resolution (checkout path). Reads the product's explicit
     * fallback pointer; variant overrides are not consulted without variant
     * context, preserving historical checkout behaviour.
     */
    @Transactional(readOnly = true)
    public Resolved resolveProduct(UUID productId) {
        return resolve(productId, null);
    }

    /**
     * Resolves the governing version for a product, optionally honouring a
     * variant override. {@code variantId} may be null (product-only path).
     */
    @Transactional(readOnly = true)
    @SuppressWarnings("null") // map(Product::activeRecipeVersionId).orElse(null) is explicit
    public Resolved resolve(UUID productId, UUID variantId) {
        if (variantId != null) {
            Optional<ProductVariant> maybeVariant = variants.findByIdAndDeletedAtIsNull(variantId);
            if (maybeVariant.isPresent()) {
                ProductVariant v = maybeVariant.get();
                // A variant override is meaningful only for its own product.
                if (Objects.equals(v.productId(), productId) && v.activeRecipeVersionId() != null) {
                    Optional<RecipeVersion> override =
                            recipes.findWithLinesById(v.activeRecipeVersionId());
                    if (override.isPresent() && isAssignableTo(override.get(), productId)) {
                        return new Resolved(Source.VARIANT, override.get());
                    }
                    // Assigned but not a valid assignable same-product version → fall
                    // through to the product pointer rather than silently mis-selling.
                }
            }
        }
        UUID fallbackId = products.findById(productId).map(Product::activeRecipeVersionId).orElse(null);
        if (fallbackId != null) {
            Optional<RecipeVersion> fallback = recipes.findWithLinesById(fallbackId);
            if (fallback.isPresent() && isAssignableTo(fallback.get(), productId)) {
                return new Resolved(Source.PRODUCT, fallback.get());
            }
        }
        return NONE;
    }

    /** Assignable = published (ACTIVE), immutable, and owned by this very product. */
    private static boolean isAssignableTo(RecipeVersion rv, UUID productId) {
        return rv.status() == RecipeStatus.ACTIVE && Objects.equals(rv.productId(), productId);
    }
}
