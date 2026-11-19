package com.smartcoffee.modules.production.application;

import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.modules.catalog.domain.Product;
import com.smartcoffee.modules.catalog.domain.ProductVariant;
import com.smartcoffee.modules.catalog.persistence.ProductRepository;
import com.smartcoffee.modules.catalog.persistence.ProductVariantRepository;
import com.smartcoffee.modules.inventory.domain.Ingredient;
import com.smartcoffee.modules.inventory.domain.UnitOfMeasure;
import com.smartcoffee.modules.inventory.persistence.IngredientRepository;
import com.smartcoffee.modules.inventory.persistence.UnitOfMeasureRepository;
import com.smartcoffee.modules.production.domain.ProductionOrderMaterial;
import com.smartcoffee.modules.production.domain.ProductionOrderOutput;
import com.smartcoffee.modules.recipe.application.RecipeLineNormalizer;
import com.smartcoffee.modules.recipe.application.RecipeVersionResolver;
import com.smartcoffee.modules.recipe.domain.RecipeStatus;
import com.smartcoffee.modules.recipe.domain.RecipeVersion;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Freezes a Production Order's target, recipe and quantity into an immutable
 * material + output snapshot (Slice 5A spec §5, Phase 5A.1 material snapshot
 * contract). Delegates recipe resolution to {@link RecipeVersionResolver} and
 * per-unit normalization to {@link RecipeLineNormalizer}, so this class holds
 * only the arithmetic that scales the normalized per-unit demand up to the
 * planned batch and looks up base-unit codes for the persisted snapshot.
 *
 * <p>Errors surface with the stable codes declared in spec §19; no SQL, no
 * stack and no supplier cost leak through.
 *
 * <p>Read-only against the recipe/catalog: neither the {@link RecipeVersion}
 * nor the Product/Variant is mutated. Product or Recipe edits after planning
 * never touch the persisted snapshot because {@link ProductionOrderMaterial}
 * rows live on the Production Order aggregate, not on the recipe.
 */
@Service
public class ProductionMaterialPlanner {

    /** Immutable outcome of planning: everything the aggregate needs to freeze. */
    public record Plan(RecipeVersion recipeVersion,
                       UUID outputInventoryItemId,
                       String outputUnitCode,
                       List<ProductionOrderMaterial> materials,
                       ProductionOrderOutput output) {
    }

    private final RecipeVersionResolver recipes;
    private final RecipeLineNormalizer normalizer;
    private final ProductRepository products;
    private final ProductVariantRepository variants;
    private final IngredientRepository ingredients;
    private final UnitOfMeasureRepository units;

    public ProductionMaterialPlanner(RecipeVersionResolver recipes, RecipeLineNormalizer normalizer,
            ProductRepository products, ProductVariantRepository variants,
            IngredientRepository ingredients, UnitOfMeasureRepository units) {
        this.recipes = recipes;
        this.normalizer = normalizer;
        this.products = products;
        this.variants = variants;
        this.ingredients = ingredients;
        this.units = units;
    }

    /**
     * Resolves and snapshots the plan for a DRAFT order about to leave for
     * PLANNED. The order's {@code variantId} may be null; the {@code
     * plannedOutputQuantity} must be strictly positive (the aggregate also
     * guards this).
     */
    @Transactional(readOnly = true)
    public Plan plan(UUID productId, UUID variantId, BigDecimal plannedOutputQuantity) {
        if (plannedOutputQuantity == null || plannedOutputQuantity.signum() <= 0) {
            throw new ValidationException("PRODUCTION_OUTPUT_QUANTITY_INVALID",
                    "Planned output quantity must be greater than zero");
        }
        Product product = products.findById(productId)
                .orElseThrow(() -> new ValidationException("PRODUCT_NOT_FOUND", "Product not found"));

        // Variant, when supplied, must exist and belong to this product.
        ProductVariant variant = null;
        if (variantId != null) {
            variant = variants.findByIdAndDeletedAtIsNull(variantId)
                    .orElseThrow(() -> new ValidationException("VARIANT_NOT_FOUND",
                            "Variant not found or deleted"));
            if (!product.id().equals(variant.productId())) {
                throw new ValidationException("PRODUCTION_ORDER_TARGET_MISMATCH",
                        "Variant does not belong to the selected product");
            }
        }

        // 1. Recipe resolution — same precedence as checkout (Variant override →
        //    Product fallback → NONE). RecipeVersionResolver validates ACTIVE
        //    + same-product ownership internally.
        RecipeVersionResolver.Resolved resolved = recipes.resolve(productId, variantId);
        if (!resolved.present()) {
            throw new ValidationException("PRODUCTION_RECIPE_REQUIRED",
                    "No assignable recipe version resolved for this target");
        }
        RecipeVersion version = resolved.recipeVersion();
        if (version.status() != RecipeStatus.ACTIVE) {
            throw new ValidationException("PRODUCTION_RECIPE_NOT_ACTIVE",
                    "Recipe version is not ACTIVE: " + version.status());
        }

        // 2. Normalize recipe lines — base-unit per sellable unit, yield applied
        //    for PROPORTIONAL_TO_YIELD, duplicates merged additively.
        List<RecipeLineNormalizer.NormalizedLine> perUnit = normalizer.normalize(version);

        // 3. Batch-load ingredients + units referenced by the lines to look up
        //    base unit codes for the persisted snapshot.
        Set<UUID> ingredientIds = new LinkedHashSet<>();
        for (RecipeLineNormalizer.NormalizedLine l : perUnit) {
            ingredientIds.add(l.ingredientId());
        }
        Map<UUID, Ingredient> ingredientById = new LinkedHashMap<>();
        ingredients.findAllById(ingredientIds).forEach(i -> ingredientById.put(i.id(), i));
        Set<UUID> baseUnitIds = new LinkedHashSet<>();
        ingredientById.values().forEach(i -> baseUnitIds.add(i.baseUnitId()));
        Map<UUID, UnitOfMeasure> unitById = new LinkedHashMap<>();
        units.findAllById(baseUnitIds).forEach(u -> unitById.put(u.id(), u));

        // 4. Scale each normalized line by the planned output quantity and
        //    round with the same policy as checkout: 4 dp, CEILING — this
        //    over-approximates demand so a completion never discovers a
        //    rounding-induced shortfall on the shop floor.
        List<ProductionOrderMaterial> materials = new ArrayList<>();
        for (RecipeLineNormalizer.NormalizedLine line : perUnit) {
            Ingredient ing = ingredientById.get(line.ingredientId());
            if (ing == null) {
                throw new ValidationException("PRODUCTION_MATERIAL_NOT_FOUND",
                        "Ingredient missing for recipe line: " + line.ingredientId());
            }
            UnitOfMeasure baseUnit = unitById.get(ing.baseUnitId());
            if (baseUnit == null) {
                throw new ValidationException("UNIT_CONVERSION_NOT_FOUND",
                        "Base unit missing for ingredient: " + ing.id());
            }
            BigDecimal plannedBase = line.perUnit()
                    .multiply(plannedOutputQuantity)
                    .setScale(4, RoundingMode.CEILING);
            // Optional lines are still snapshotted so the shop floor sees the
            // full demand plan; a Phase 5A.2 completion may skip consuming an
            // optional line without violating the recipe.
            materials.add(ProductionOrderMaterial.plan(
                    ing.id(),
                    plannedBase,
                    baseUnit.code(),
                    null, // source_recipe_line_id is available per-merged-line but not preserved through normalization
                    BigDecimal.ZERO, // wastage was already folded into perUnit by the normalizer
                    line.displayOrder()));
        }

        // 5. Resolve output inventory item — Variant → Product → error
        //    (Decision 1 rule 10: variant override wins over product fallback).
        UUID outputIngredientId = null;
        if (variant != null && variant.outputIngredientId() != null) {
            outputIngredientId = variant.outputIngredientId();
        } else if (product.outputIngredientId() != null) {
            outputIngredientId = product.outputIngredientId();
        }
        if (outputIngredientId == null) {
            throw new ValidationException("PRODUCTION_OUTPUT_ITEM_REQUIRED",
                    "Neither the variant nor the product declares an output inventory item; "
                            + "assign one on the Product or Variant before planning");
        }

        Ingredient outputIngredient = ingredients.findById(outputIngredientId)
                .orElseThrow(() -> new ValidationException("PRODUCTION_OUTPUT_ITEM_MISMATCH",
                        "Output inventory item does not exist"));
        if (!outputIngredient.active()) {
            throw new ValidationException("PRODUCTION_OUTPUT_ITEM_MISMATCH",
                    "Output inventory item is inactive");
        }
        UnitOfMeasure outputBaseUnit = units.findById(outputIngredient.baseUnitId())
                .orElseThrow(() -> new ValidationException("UNIT_CONVERSION_NOT_FOUND",
                        "Base unit missing for output ingredient"));

        ProductionOrderOutput output = ProductionOrderOutput.plan(
                outputIngredient.id(),
                plannedOutputQuantity,
                outputBaseUnit.code());

        return new Plan(version, outputIngredient.id(), outputBaseUnit.code(), materials, output);
    }
}
