package com.smartcoffee.modules.recipe.application;

import com.smartcoffee.modules.recipe.application.RecipeVersionResolver.Resolved;
import java.math.*;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Checkout ingredient-demand calculator. Production checkout entry point;
 * recipe lookup routes through the shared {@link RecipeVersionResolver}
 * (product-only path = the explicit product fallback pointer since Slice
 * 2B.1) so resolution is defined in exactly one place. Demand arithmetic is
 * unchanged: a product without an assigned, assignable recipe contributes zero
 * demand (skipped, never fabricated), and each line contributes
 * {@code line.demand(qty)} rounded to 4 dp CEILING. V044 backfilled the
 * pointer from the prior single ACTIVE version, so Product-only output is
 * byte-for-byte identical to pre-2B.1 checkout behaviour.
 */
@Service
public class RecipeDemandService {
    public record ProductDemand(UUID productId, int quantity) {
    }

    private final RecipeVersionResolver resolver;

    public RecipeDemandService(RecipeVersionResolver resolver) {
        this.resolver = resolver;
    }

    @Transactional(readOnly = true)
    @SuppressWarnings("null") // merge(BigDecimal::add) operands are non-null by construction
    public Map<UUID, BigDecimal> calculate(List<ProductDemand> products) {
        Map<UUID, BigDecimal> result = new TreeMap<>();

        for (ProductDemand product : products) {
            if (product.quantity() <= 0) {
                throw new IllegalArgumentException("Product quantity must be positive");
            }

            // Best-effort over DEFINED recipes: a product without an ACTIVE,
            // in-effect recipe contributes zero demand instead of aborting
            // checkout (not every catalog product is recipe-based). Missing demand
            // is skipped, never fabricated. Product-only resolution preserves the
            // exact pre-Slice-2A behaviour (variant overrides are not consulted).
            Resolved resolved = resolver.resolveProduct(product.productId());
            if (!resolved.present()) {
                continue;
            }

            for (var line : resolved.recipeVersion().lines()) {
                BigDecimal demand = line.demand(product.quantity())
                        .setScale(4, RoundingMode.CEILING);
                result.merge(line.ingredientId(), demand, BigDecimal::add);
            }
        }

        return result;
    }
}
