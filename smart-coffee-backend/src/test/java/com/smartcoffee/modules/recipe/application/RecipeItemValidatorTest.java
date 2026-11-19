package com.smartcoffee.modules.recipe.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.modules.inventory.domain.Ingredient;
import com.smartcoffee.modules.inventory.domain.UnitOfMeasure;
import com.smartcoffee.modules.inventory.domain.UnitType;
import com.smartcoffee.modules.inventory.persistence.IngredientRepository;
import com.smartcoffee.modules.inventory.persistence.UnitOfMeasureRepository;
import com.smartcoffee.modules.recipe.api.request.RecipeItemRequest;
import com.smartcoffee.modules.recipe.domain.RecipeScalingRule;
import com.smartcoffee.modules.recipe.domain.RecipeVersionLine;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RecipeItemValidator}: referential + business guards that
 * the DB cannot express (ingredient active, one line per ingredient, unit
 * existence/active/dimension compatibility). Range checks (qty > 0) live in the
 * domain factory / Bean Validation and are not re-tested here.
 */
class RecipeItemValidatorTest {

    private static final UUID INGREDIENT = UUID.fromString("70000000-0000-0000-0000-000000000001");
    private static final UUID BASE_UNIT = UUID.fromString("60000000-0000-0000-0000-000000000001");
    private static final UUID LINE_UNIT = UUID.fromString("60000000-0000-0000-0000-000000000002");

    private IngredientRepository ingredients;
    private UnitOfMeasureRepository units;
    private RecipeItemValidator validator;

    @BeforeEach
    void setup() {
        ingredients = mock(IngredientRepository.class);
        units = mock(UnitOfMeasureRepository.class);
        validator = new RecipeItemValidator(ingredients, units);
    }

    private void stubActiveIngredient(UnitType baseType) {
        Ingredient ing = mock(Ingredient.class);
        when(ing.active()).thenReturn(true);
        when(ing.baseUnitId()).thenReturn(BASE_UNIT);
        when(ingredients.findById(INGREDIENT)).thenReturn(Optional.of(ing));
        UnitOfMeasure base = mock(UnitOfMeasure.class);
        when(base.type()).thenReturn(baseType);
        when(units.findById(BASE_UNIT)).thenReturn(Optional.of(base));
    }

    private RecipeItemRequest item(UUID unitId) {
        return new RecipeItemRequest(INGREDIENT, new BigDecimal("0.5"), BigDecimal.ZERO,
                unitId, RecipeScalingRule.FIXED, 0, false, null);
    }

    @Test
    void unknownIngredientRejected() {
        when(ingredients.findById(INGREDIENT)).thenReturn(Optional.empty());
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.build(List.of(item(null))));
        assertEquals("INGREDIENT_NOT_FOUND", ex.code());
    }

    @Test
    void inactiveIngredientRejected() {
        Ingredient ing = mock(Ingredient.class);
        when(ing.active()).thenReturn(false);
        when(ingredients.findById(INGREDIENT)).thenReturn(Optional.of(ing));
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.build(List.of(item(null))));
        assertEquals("INGREDIENT_INACTIVE", ex.code());
    }

    @Test
    void duplicateIngredientRejected() {
        stubActiveIngredient(UnitType.MASS);
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.build(List.of(item(null), item(null))));
        assertEquals("DUPLICATE_RECIPE_INGREDIENT", ex.code());
    }

    @Test
    void incompatibleUnitDimensionRejected() {
        stubActiveIngredient(UnitType.MASS);
        UnitOfMeasure lineUnit = mock(UnitOfMeasure.class);
        when(lineUnit.active()).thenReturn(true);
        when(lineUnit.type()).thenReturn(UnitType.VOLUME);
        when(units.findById(LINE_UNIT)).thenReturn(Optional.of(lineUnit));
        ValidationException ex = assertThrows(ValidationException.class,
                () -> validator.build(List.of(item(LINE_UNIT))));
        assertEquals("UNIT_INCOMPATIBLE", ex.code());
    }

    @Test
    void validLineWithoutUnitBuildsWithFixedScaling() {
        stubActiveIngredient(UnitType.MASS);
        List<RecipeVersionLine> lines = validator.build(List.of(item(null)));
        assertEquals(1, lines.size());
        assertEquals(RecipeScalingRule.FIXED, lines.get(0).scalingRule());
        assertEquals(0, new BigDecimal("0.5").compareTo(lines.get(0).quantityRequiredBase()));
    }
}
