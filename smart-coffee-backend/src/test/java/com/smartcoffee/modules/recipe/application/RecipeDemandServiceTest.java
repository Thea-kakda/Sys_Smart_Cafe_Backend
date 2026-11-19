package com.smartcoffee.modules.recipe.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.smartcoffee.modules.recipe.application.RecipeDemandService.ProductDemand;
import com.smartcoffee.modules.recipe.application.RecipeVersionResolver.Resolved;
import com.smartcoffee.modules.recipe.application.RecipeVersionResolver.Source;
import com.smartcoffee.modules.recipe.domain.RecipeVersion;
import com.smartcoffee.modules.recipe.domain.RecipeVersionLine;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Focused unit tests for {@link RecipeDemandService}. Proves that ingredient
 * demand is best-effort over DEFINED recipes: a product with no ACTIVE recipe
 * contributes zero demand instead of aborting checkout. Slice 2A routes lookup
 * through the shared {@link RecipeVersionResolver} (product-only path); these
 * tests assert the demand arithmetic and skip behaviour are unchanged.
 */
class RecipeDemandServiceTest {

    private static final UUID PRODUCT_WITH_RECIPE = UUID.fromString("30000000-0000-0000-0000-000000000001");
    private static final UUID PRODUCT_WITHOUT_RECIPE = UUID.fromString("30000000-0000-0000-0000-000000000099");
    private static final UUID COFFEE_BEAN = UUID.fromString("70000000-0000-0000-0000-000000000001");

    private RecipeVersionResolver resolver;
    private RecipeDemandService service;

    @BeforeEach
    void setup() {
        resolver = mock(RecipeVersionResolver.class);
        service = new RecipeDemandService(resolver);
    }

    private RecipeVersion recipeWithLine(UUID ingredient, BigDecimal demandForTwo) {
        RecipeVersionLine line = mock(RecipeVersionLine.class);
        when(line.ingredientId()).thenReturn(ingredient);
        when(line.demand(eq(2))).thenReturn(demandForTwo);
        RecipeVersion recipe = mock(RecipeVersion.class);
        when(recipe.lines()).thenReturn(List.of(line));
        return recipe;
    }

    @Test
    void productWithoutActiveRecipeIsSkippedAndYieldsNoDemand() {
        when(resolver.resolveProduct(PRODUCT_WITHOUT_RECIPE)).thenReturn(new Resolved(Source.NONE, null));

        Map<UUID, BigDecimal> result = service.calculate(List.of(new ProductDemand(PRODUCT_WITHOUT_RECIPE, 2)));

        assertTrue(result.isEmpty(), "recipe-less product must contribute zero ingredient demand, not throw");
    }

    @Test
    void productWithRecipeContributesIngredientDemand() {
        RecipeVersion recipe = recipeWithLine(COFFEE_BEAN, new BigDecimal("36"));
        when(resolver.resolveProduct(PRODUCT_WITH_RECIPE)).thenReturn(new Resolved(Source.PRODUCT, recipe));

        Map<UUID, BigDecimal> result = service.calculate(List.of(new ProductDemand(PRODUCT_WITH_RECIPE, 2)));

        assertEquals(0, new BigDecimal("36.0000").compareTo(result.get(COFFEE_BEAN)));
    }

    @Test
    void mixedCartSumsOnlyDefinedRecipesAndDoesNotThrow() {
        RecipeVersion recipe = recipeWithLine(COFFEE_BEAN, new BigDecimal("36"));
        when(resolver.resolveProduct(PRODUCT_WITH_RECIPE)).thenReturn(new Resolved(Source.PRODUCT, recipe));
        when(resolver.resolveProduct(PRODUCT_WITHOUT_RECIPE)).thenReturn(new Resolved(Source.NONE, null));

        Map<UUID, BigDecimal> result = service.calculate(
                List.of(new ProductDemand(PRODUCT_WITH_RECIPE, 2), new ProductDemand(PRODUCT_WITHOUT_RECIPE, 5)));

        assertEquals(1, result.size(), "only the product with a defined recipe contributes demand");
        assertEquals(0, new BigDecimal("36.0000").compareTo(result.get(COFFEE_BEAN)));
    }

    @Test
    void nonPositiveQuantityStillRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> service.calculate(List.of(new ProductDemand(PRODUCT_WITH_RECIPE, 0))));
        verify(resolver, never()).resolveProduct(any());
    }
}
