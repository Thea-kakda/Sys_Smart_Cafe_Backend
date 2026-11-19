package com.smartcoffee.modules.recipe.application;

import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.modules.inventory.domain.Ingredient;
import com.smartcoffee.modules.inventory.domain.UnitOfMeasure;
import com.smartcoffee.modules.inventory.persistence.IngredientRepository;
import com.smartcoffee.modules.inventory.persistence.UnitOfMeasureRepository;
import com.smartcoffee.modules.recipe.domain.RecipeScalingRule;
import com.smartcoffee.modules.recipe.domain.RecipeVersion;
import com.smartcoffee.modules.recipe.domain.RecipeVersionLine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Turns a resolved {@link RecipeVersion}'s lines into per-single-sellable-unit
 * ingredient demand, expressed in each ingredient's base inventory unit. This is
 * the base layer the {@code ConsumptionPlanService} feeds to the effect stage and
 * finally scales by the requested product quantity.
 *
 * <p>Scaling semantics (Slice 2A definition, honored verbatim):
 * <ul>
 *   <li>{@link RecipeScalingRule#FIXED} — {@code perUnit = quantity_required_base ×
 *     (1 + wastage%)} for one sellable unit. With a null line unit this equals
 *     {@link RecipeVersionLine#demand(int)} at quantity 1, i.e. the checkout value.</li>
 *   <li>{@link RecipeScalingRule#PROPORTIONAL_TO_YIELD} — the line describes a
 *     whole-batch amount, so {@code perUnit = (quantity_required_base / yield) ×
 *     (1 + wastage%)}. Yield is validated &gt; 0.</li>
 * </ul>
 *
 * <p>Duplicate ingredient lines (possible only in historical data, since new drafts
 * reject duplicates) merge additively; the earliest {@code displayOrder} and the
 * logical AND of {@code optional} are retained. Quantities are kept UNROUNDED here
 * — the single rounding to scale 4 CEILING happens once, after product-quantity
 * scaling, so checkout-compatible values are reproduced exactly.
 *
 * <p>Read-only: neither the {@link RecipeVersion} nor its lines are mutated. Lines
 * and their ingredients / units are batch-loaded to avoid N+1.
 */
@Component
public class RecipeLineNormalizer {

    private final IngredientRepository ingredients;
    private final UnitOfMeasureRepository units;

    public RecipeLineNormalizer(IngredientRepository ingredients, UnitOfMeasureRepository units) {
        this.ingredients = ingredients;
        this.units = units;
    }

    /** One merged, base-unit, per-unit requirement produced from the recipe lines. */
    public record NormalizedLine(UUID ingredientId, BigDecimal perUnit, int displayOrder, boolean optional) {
    }

    @SuppressWarnings("null") // Map.merge/Comparator operands are non-null by construction
    public List<NormalizedLine> normalize(RecipeVersion version) {
        List<RecipeVersionLine> ordered = new ArrayList<>(version.lines());
        ordered.sort(java.util.Comparator.comparingInt(RecipeVersionLine::displayOrder)
                .thenComparing(RecipeVersionLine::id));

        // Batch-load every ingredient referenced by the lines.
        Set<UUID> ingredientIds = new HashSet<>();
        Set<UUID> unitIds = new HashSet<>();
        for (RecipeVersionLine line : ordered) {
            ingredientIds.add(line.ingredientId());
            if (line.unitId() != null) {
                unitIds.add(line.unitId());
            }
        }
        Map<UUID, Ingredient> ingredientById = new LinkedHashMap<>();
        ingredients.findAllById(ingredientIds).forEach(i -> ingredientById.put(i.id(), i));
        ingredientById.values().forEach(i -> unitIds.add(i.baseUnitId()));
        Map<UUID, UnitOfMeasure> unitById = new LinkedHashMap<>();
        units.findAllById(unitIds).forEach(u -> unitById.put(u.id(), u));

        BigDecimal yield = version.yieldQuantity();
        if (yield == null || yield.signum() <= 0) {
            throw new ValidationException("INVALID_RECIPE_YIELD", "Recipe yield must be greater than zero");
        }

        // Merge by ingredient, preserving first-seen (display) order.
        Map<UUID, BigDecimal> perUnit = new LinkedHashMap<>();
        Map<UUID, Integer> displayOrder = new LinkedHashMap<>();
        Map<UUID, Boolean> optionalFlags = new LinkedHashMap<>();

        for (RecipeVersionLine line : ordered) {
            Ingredient ingredient = ingredientById.get(line.ingredientId());
            if (ingredient == null) {
                throw new ValidationException("INGREDIENT_NOT_FOUND",
                        "Recipe references unknown ingredient: " + line.ingredientId());
            }
            UnitOfMeasure lineUnit = line.unitId() == null ? null : unitById.get(line.unitId());
            UnitOfMeasure baseUnit = unitById.get(ingredient.baseUnitId());

            BigDecimal baseQty = UnitConverter.toBaseUnit(line.quantityRequiredBase(), lineUnit, baseUnit);
            BigDecimal withWastage = baseQty.multiply(BigDecimal.ONE.add(line.wastagePercent().movePointLeft(2)));

            BigDecimal contribution = (line.scalingRule() == RecipeScalingRule.PROPORTIONAL_TO_YIELD)
                    ? withWastage.divide(yield, 10, RoundingMode.HALF_UP)
                    : withWastage;

            UUID key = line.ingredientId();
            perUnit.merge(key, contribution, BigDecimal::add);
            displayOrder.merge(key, line.displayOrder(), Math::min);
            optionalFlags.merge(key, line.optional(), (a, b) -> a && b);
        }

        List<NormalizedLine> result = new ArrayList<>();
        perUnit.forEach((id, qty) -> result.add(
                new NormalizedLine(id, qty, displayOrder.get(id), optionalFlags.getOrDefault(id, false))));
        result.sort(java.util.Comparator.comparingInt(NormalizedLine::displayOrder)
                .thenComparing(NormalizedLine::ingredientId));
        return result;
    }
}
