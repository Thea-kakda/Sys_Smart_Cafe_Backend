package com.smartcoffee.modules.recipe.api.request;

import com.smartcoffee.modules.cart.api.SelectedCustomizationRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Body for the management-only consumption-plan preview (Slice 2B). The client may
 * ONLY describe which configurable item it wants planned and how it is customized;
 * it never supplies ingredient ids, quantities, recipe ids, effect types, prices or
 * multipliers — those are loaded authoritatively from the database.
 *
 * <p>{@code selectedOptions} reuses the cart's validated selection shape so planning
 * and checkout agree on what a customization means.
 */
public record ConsumptionPlanPreviewRequest(
        @NotNull UUID productId,
        UUID variantId,
        @NotNull @DecimalMin(value = "0", inclusive = false) BigDecimal productQuantity,
        @Valid List<SelectedCustomizationRequest> selectedOptions) {
}
