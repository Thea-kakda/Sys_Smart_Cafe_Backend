package com.smartcoffee.modules.recipe.api.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Replaces the full set of a DRAFT version's lines (clone-on-modify semantics:
 * the caller always submits the complete desired list). DRAFT-only; a non-DRAFT
 * target returns 409 RECIPE_VERSION_IMMUTABLE. {@code expectedVersion} echoes
 * the optimistic lock.
 */
public record ReplaceRecipeItemsRequest(
        @NotNull Long expectedVersion,
        @Valid @NotNull List<RecipeItemRequest> items) {
}
