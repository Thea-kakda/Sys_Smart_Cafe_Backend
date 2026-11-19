package com.smartcoffee.modules.recipe.api.request;

import jakarta.validation.constraints.NotNull;

/**
 * Activates a DRAFT recipe version. The service atomically validates lines,
 * locks the product scope, supersedes the prior ACTIVE version, promotes this
 * one, and records the actor/timestamp. {@code expectedVersion} echoes the
 * optimistic lock (409 RECIPE_VERSION_CONFLICT on stale submit).
 */
public record ActivateRecipeRequest(
        @NotNull Long expectedVersion) {
}
