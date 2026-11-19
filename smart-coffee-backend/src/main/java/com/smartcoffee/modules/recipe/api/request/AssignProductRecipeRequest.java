package com.smartcoffee.modules.recipe.api.request;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

/**
 * Assigns (or clears) the Product's fallback recipe version (Slice 2B.1).
 * A null {@code recipeVersionId} removes the fallback pointer; the caller must
 * echo the product's current optimistic-lock {@code version} so concurrent
 * assignment edits are rejected with 409 instead of silently clobbering.
 */
public record AssignProductRecipeRequest(
        UUID recipeVersionId,
        @NotNull Long expectedProductVersion) {
}
