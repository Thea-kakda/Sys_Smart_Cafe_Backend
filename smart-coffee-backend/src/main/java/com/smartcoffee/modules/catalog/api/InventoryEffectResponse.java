package com.smartcoffee.modules.catalog.api;

import com.smartcoffee.modules.catalog.domain.InventoryEffectType;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Read projection of an option's inventory-effect configuration. When the
 * attached option has {@code NONE} the auxiliary fields are null; the shape
 * mirrors what the DB CHECK constraint (ck_option_effect_shape in V039) will
 * actually accept on write.
 */
public record InventoryEffectResponse(
        UUID optionId,
        UUID groupId,
        String optionCode,
        String optionName,
        BigDecimal additionalPrice,
        InventoryEffectType type,
        UUID sourceIngredientId,
        UUID targetIngredientId,
        BigDecimal effectQuantity,
        BigDecimal effectMultiplier,
        @Deprecated UUID replacesOptionId,
        long version) {
}
