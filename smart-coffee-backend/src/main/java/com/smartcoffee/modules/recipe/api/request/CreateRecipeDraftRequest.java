package com.smartcoffee.modules.recipe.api.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Creates a new DRAFT recipe version for a product. The version number is
 * reserved by the service (max + 1 within the product), so it is not accepted
 * here. {@code items} may be empty (a draft can be filled in later via
 * replace-items); activation requires at least one valid line.
 */
public record CreateRecipeDraftRequest(
        @Positive BigDecimal yieldQuantity,
        UUID yieldUnitId,
        String notes,
        @Valid List<RecipeItemRequest> items) {
}
