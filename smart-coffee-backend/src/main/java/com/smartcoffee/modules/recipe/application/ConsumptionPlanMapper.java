package com.smartcoffee.modules.recipe.application;

import com.smartcoffee.modules.recipe.api.ConsumptionPlanLineResponse;
import com.smartcoffee.modules.recipe.api.ConsumptionPlanPreviewResponse;
import java.util.List;
import org.springframework.stereotype.Component;

/** Projects the immutable application plan onto the management response DTO. */
@Component
public class ConsumptionPlanMapper {

    public ConsumptionPlanPreviewResponse toResponse(CalculatedConsumptionPlan plan) {
        List<ConsumptionPlanLineResponse> lines = plan.lines().stream()
                .map(l -> new ConsumptionPlanLineResponse(
                        l.ingredientId(), l.ingredientCode(), l.ingredientName(), l.quantity(),
                        l.baseUnitCode(), l.source(), l.appliedOptionIds(), l.displayOrder(), l.optional()))
                .toList();
        return new ConsumptionPlanPreviewResponse(
                plan.productId(), plan.variantId(), plan.recipeVersionId(), plan.recipeVersionNumber(),
                plan.resolutionSource(), plan.requestedProductQuantity(), plan.resolvedAt(),
                lines, plan.warnings());
    }
}
