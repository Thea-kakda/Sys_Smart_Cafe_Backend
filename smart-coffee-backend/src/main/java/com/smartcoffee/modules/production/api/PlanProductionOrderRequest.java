package com.smartcoffee.modules.production.api;

/**
 * Freezes a DRAFT order's target/recipe/quantity into an immutable material +
 * output snapshot (Slice 5A §23, Phase 5A.1). Only the optimistic version is
 * carried — no explicit recipe override is accepted because Decision doc
 * §5 states production planning always uses the Variant → Product cascade
 * resolved by {@code RecipeVersionResolver}.
 */
public record PlanProductionOrderRequest(long expectedVersion) {
}
