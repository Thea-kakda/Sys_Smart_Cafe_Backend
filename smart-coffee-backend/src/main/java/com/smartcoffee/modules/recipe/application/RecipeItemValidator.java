package com.smartcoffee.modules.recipe.application;

import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.modules.inventory.domain.Ingredient;
import com.smartcoffee.modules.inventory.domain.UnitOfMeasure;
import com.smartcoffee.modules.inventory.persistence.IngredientRepository;
import com.smartcoffee.modules.inventory.persistence.UnitOfMeasureRepository;
import com.smartcoffee.modules.recipe.api.request.RecipeItemRequest;
import com.smartcoffee.modules.recipe.domain.RecipeVersionLine;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Turns validated {@link RecipeItemRequest}s into domain {@link RecipeVersionLine}s
 * for a DRAFT. Enforces the referential + business rules that the DB cannot:
 *
 * <ul>
 *   <li>Ingredient exists and is active (400 INGREDIENT_NOT_FOUND / INGREDIENT_INACTIVE).</li>
 *   <li>One line per ingredient per version (409 DUPLICATE_RECIPE_INGREDIENT —
 *     mirrors the {@code UNIQUE(recipe_version_id, ingredient_id)} constraint).</li>
 *   <li>Unit, when supplied, exists and is active (400 UNIT_NOT_FOUND / UNIT_INACTIVE).</li>
 *   <li>Unit shares the ingredient base unit's dimension (400 UNIT_INCOMPATIBLE).
 *     No cross-dimension conversion is inferred in Slice 2A.</li>
 * </ul>
 *
 * <p>Quantity / wastage range checks live in {@link RecipeVersionLine#create} and
 * Bean Validation on the request DTO.
 */
@Component
public class RecipeItemValidator {

    private final IngredientRepository ingredients;
    private final UnitOfMeasureRepository units;

    public RecipeItemValidator(IngredientRepository ingredients, UnitOfMeasureRepository units) {
        this.ingredients = ingredients;
        this.units = units;
    }

    public List<RecipeVersionLine> build(List<RecipeItemRequest> items) {
        List<RecipeVersionLine> lines = new ArrayList<>();
        Set<UUID> seenIngredients = new HashSet<>();
        int fallbackOrder = 0;
        for (RecipeItemRequest item : items) {
            if (item.ingredientId() == null) {
                throw new ValidationException("RECIPE_ITEM_INVALID", "Recipe line requires an ingredient");
            }
            if (!seenIngredients.add(item.ingredientId())) {
                throw new ValidationException("DUPLICATE_RECIPE_INGREDIENT",
                        "Ingredient appears more than once in the same recipe version: " + item.ingredientId());
            }
            Ingredient ingredient = ingredients.findById(item.ingredientId())
                    .orElseThrow(() -> new ValidationException("INGREDIENT_NOT_FOUND",
                            "Unknown ingredient: " + item.ingredientId()));
            if (!ingredient.active()) {
                throw new ValidationException("INGREDIENT_INACTIVE",
                        "Ingredient is inactive: " + item.ingredientId());
            }
            if (item.unitId() != null) {
                UnitOfMeasure unit = units.findById(item.unitId())
                        .orElseThrow(() -> new ValidationException("UNIT_NOT_FOUND",
                                "Unknown unit of measure: " + item.unitId()));
                if (!unit.active()) {
                    throw new ValidationException("UNIT_INACTIVE", "Unit of measure is inactive: " + item.unitId());
                }
                UnitOfMeasure base = units.findById(ingredient.baseUnitId()).orElse(null);
                if (base != null && base.type() != unit.type()) {
                    throw new ValidationException("UNIT_INCOMPATIBLE",
                            "Line unit dimension (" + unit.type() + ") does not match the ingredient base unit ("
                                    + base.type() + ") for ingredient " + item.ingredientId());
                }
            }
            int order = item.displayOrder() >= 0 ? item.displayOrder() : fallbackOrder;
            lines.add(RecipeVersionLine.create(item.ingredientId(), item.quantityRequiredBase(),
                    item.wastagePercent(), item.unitId(), item.scalingRule(), order,
                    item.optional(), item.notes()));
            fallbackOrder++;
        }
        return lines;
    }
}
