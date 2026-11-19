package com.smartcoffee.modules.inventory.api;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Full-replacement ingredient master update (M2). Carries the optimistic-lock
 * {@code version} echoed from the last read; a stale value maps to
 * {@code 409 VERSION_CONFLICT}. Setting {@code active=false} deactivates (no
 * hard delete).
 */
public record UpdateIngredientRequest(
        @NotBlank @Size(max = 50) String code,
        @NotBlank @Size(max = 150) String name,
        @NotNull UUID baseUnitId,
        @NotNull @DecimalMin("0.0000") @Digits(integer = 14, fraction = 4) BigDecimal standardCost,
        Boolean trackInventory,
        Boolean trackBatches,
        @Positive Integer shelfLifeDays,
        Boolean active,
        @NotNull @PositiveOrZero Long version) {

    public boolean trackInventoryOrDefault() {
        return !Boolean.FALSE.equals(trackInventory);
    }

    public boolean trackBatchesOrFalse() {
        return Boolean.TRUE.equals(trackBatches);
    }

    public boolean activeOrDefault() {
        return !Boolean.FALSE.equals(active);
    }
}
