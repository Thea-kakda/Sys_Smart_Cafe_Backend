package com.smartcoffee.modules.cart.application;

import com.smartcoffee.modules.cart.api.SelectedCustomizationRequest;
import com.smartcoffee.modules.cart.domain.CartItemCustomization;
import com.smartcoffee.modules.inventory.application.ReservationDemand;
import com.smartcoffee.modules.inventory.domain.Ingredient;
import com.smartcoffee.modules.inventory.persistence.IngredientRepository;
import com.smartcoffee.modules.recipe.application.CalculatedConsumptionLine;
import com.smartcoffee.modules.recipe.application.CalculatedConsumptionPlan;
import com.smartcoffee.modules.recipe.application.ConsumptionPlanService;
import java.math.BigDecimal;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Turns one cart line's configuration into the inventory demand that has to be
 * held (Slice 2C §6).
 *
 * <p>The demand is <b>never</b> derived here: {@link ConsumptionPlanService} is
 * the single authoritative calculator, and only its normalized, base-unit,
 * already-quantity-scaled lines are used. Client-supplied ingredient ids,
 * quantities, units or effects are never consulted — the plan ignores them by
 * construction.
 *
 * <p>Only stock-tracked ingredients ({@code ingredients.track_inventory}) become
 * reservable demand: an untracked item (a service charge, a non-stock garnish)
 * has no balance row and must not block a sale. Everything the plan says about
 * stock-tracked ingredients is reserved exactly as calculated.
 */
@Service
public class CartDemandService {

    private final ConsumptionPlanService plans;
    private final IngredientRepository ingredients;

    public CartDemandService(ConsumptionPlanService plans, IngredientRepository ingredients) {
        this.plans = plans;
        this.ingredients = ingredients;
    }

    @Transactional(readOnly = true)
    public ReservationDemand demand(UUID productId, UUID variantId, int quantity,
            List<CartItemCustomization> customizations) {
        List<SelectedCustomizationRequest> selected = customizations.stream()
                .map(c -> new SelectedCustomizationRequest(c.groupId(), c.optionId(), c.quantity()))
                .toList();
        CalculatedConsumptionPlan plan = plans.plan(productId, variantId, BigDecimal.valueOf(quantity), selected);
        String source = plan.resolutionSource() == null ? "NONE" : plan.resolutionSource().name();
        if (plan.lines().isEmpty()) {
            return new ReservationDemand(Map.of(), Map.of(), plan.recipeVersionId(), plan.recipeVersionNumber(),
                    source);
        }

        Map<UUID, Boolean> tracked = new HashMap<>();
        plan.lines().forEach(line -> tracked.put(line.ingredientId(), true));
        Map<UUID, Ingredient> loaded = new HashMap<>();
        ingredients.findAllById(tracked.keySet()).forEach(i -> loaded.put(i.id(), i));

        Map<UUID, BigDecimal> lines = new LinkedHashMap<>();
        Map<UUID, String> baseUnits = new LinkedHashMap<>();
        for (CalculatedConsumptionLine line : plan.lines()) {
            Ingredient ingredient = loaded.get(line.ingredientId());
            // Unknown ingredient is a catalog/data fault: keep it so the reservation
            // step fails loudly (INVENTORY_BALANCE_NOT_FOUND) instead of silently
            // under-reserving.
            if (ingredient != null && !ingredient.trackInventory()) {
                continue;
            }
            lines.merge(line.ingredientId(), line.quantity(), (left, right) -> left.add(right));
            baseUnits.put(line.ingredientId(), line.baseUnitCode());
        }
        return new ReservationDemand(lines, baseUnits, plan.recipeVersionId(), plan.recipeVersionNumber(), source);
    }
}
