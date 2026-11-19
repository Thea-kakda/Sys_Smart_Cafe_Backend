package com.smartcoffee.modules.inventory.api;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Creates an ingredient master record (M2). Field lengths mirror the V005
 * schema (code &lt;=50, name &lt;=150); the base unit must exist.
 * {@code trackBatches}/{@code shelfLifeDays} are the V036 batch-tracking
 * options (opt-in, consumed by M3). {@code active} defaults to true.
 */
public record CreateIngredientRequest(
        @NotBlank @Size(max = 50) String code,
        @NotBlank @Size(max = 150) String name,
        @NotNull UUID baseUnitId,
        @NotNull @DecimalMin("0.0000") @Digits(integer = 14, fraction = 4) BigDecimal standardCost,
        Boolean trackInventory,
        Boolean trackBatches,
        @Positive Integer shelfLifeDays,
        Boolean active) {

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
