package com.smartcoffee.modules.recipe.api.request;

import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Updates the mutable header of a DRAFT recipe version. Rejected (409
 * RECIPE_VERSION_IMMUTABLE) for any non-DRAFT status. {@code expectedVersion}
 * is the optimistic-lock echo; a mismatch returns 409 RECIPE_VERSION_CONFLICT.
 */
public record UpdateRecipeDraftRequest(
        @NotNull Long expectedVersion,
        BigDecimal yieldQuantity,
        UUID yieldUnitId,
        String notes) {
}
