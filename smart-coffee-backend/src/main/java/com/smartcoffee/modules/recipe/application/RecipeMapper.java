package com.smartcoffee.modules.recipe.application;

import com.smartcoffee.modules.catalog.domain.Product;
import com.smartcoffee.modules.catalog.domain.ProductVariant;
import com.smartcoffee.modules.recipe.api.ProductRecipeResponse;
import com.smartcoffee.modules.recipe.api.RecipeLineResponse;
import com.smartcoffee.modules.recipe.api.RecipeVersionResponse;
import com.smartcoffee.modules.recipe.api.VariantRecipeResponse;
import com.smartcoffee.modules.recipe.domain.RecipeVersion;
import com.smartcoffee.modules.recipe.domain.RecipeVersionLine;
import java.util.Comparator;
import java.util.List;
import org.springframework.stereotype.Component;

/** Pure entity-to-DTO projections for the recipe management surface. */
@Component
public class RecipeMapper {

    @SuppressWarnings("null") // Comparator.comparingInt over a non-null line list
    public RecipeVersionResponse toResponse(RecipeVersion v) {
        List<RecipeLineResponse> lines = v.lines().stream()
                .sorted(Comparator.comparingInt(RecipeVersionLine::displayOrder))
                .map(this::toLineResponse)
                .toList();
        return new RecipeVersionResponse(
                v.id(), v.productId(), v.versionNumber(), v.status(),
                v.effectiveFrom(), v.effectiveTo(), v.yieldQuantity(), v.yieldUnitId(), v.notes(),
                v.activatedAt(), v.activatedBy(), v.createdAt(), v.updatedAt(), v.version(), lines);
    }

    public RecipeLineResponse toLineResponse(RecipeVersionLine l) {
        return new RecipeLineResponse(
                l.id(), l.ingredientId(), l.quantityRequiredBase(), l.wastagePercent(),
                l.unitId(), l.scalingRule(), l.displayOrder(), l.optional(), l.notes());
    }

    public VariantRecipeResponse toVariantResponse(ProductVariant v) {
        return new VariantRecipeResponse(v.id(), v.productId(), v.activeRecipeVersionId(), v.version());
    }

    public ProductRecipeResponse toProductResponse(Product p) {
        return new ProductRecipeResponse(p.id(), p.activeRecipeVersionId(), p.version());
    }
}
