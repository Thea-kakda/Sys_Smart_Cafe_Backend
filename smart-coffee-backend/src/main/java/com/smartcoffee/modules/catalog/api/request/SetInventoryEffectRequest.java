package com.smartcoffee.modules.catalog.api.request;

import com.smartcoffee.modules.catalog.domain.InventoryEffectType;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Upserts the inventory effect attached to a customization option. Slice 1A
 * adopts an explicit source/target split (V041) so the CHECK constraint can
 * enforce per-type shape without relying on an option-to-option reference:
 *
 * <ul>
 *   <li>NONE       — clears every auxiliary field.</li>
 *   <li>ADD        — requires targetIngredientId + positive effectQuantity.</li>
 *   <li>REMOVE     — requires sourceIngredientId; effectQuantity optional
 *                   (when null the full calculated source amount is removed).</li>
 *   <li>REPLACE    — requires both sourceIngredientId and targetIngredientId
 *                   and they must differ; optional positive effectQuantity
 *                   overrides the preserved source amount.</li>
 *   <li>MULTIPLY   — requires sourceIngredientId + non-negative effectMultiplier
 *                   (0.00 no sugar / 0.50 / 1.00 / 1.50 extra).</li>
 * </ul>
 *
 * <p>{@code replacesOptionId} is retained for read compatibility with rows
 * written before V041 but is never required for a new write.
 */
public record SetInventoryEffectRequest(
        @NotNull InventoryEffectType type,
        UUID sourceIngredientId,
        UUID targetIngredientId,
        BigDecimal effectQuantity,
        BigDecimal effectMultiplier,
        @Deprecated UUID replacesOptionId) {
}
