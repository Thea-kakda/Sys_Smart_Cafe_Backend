package com.smartcoffee.modules.inventory.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Converts low-stock reorder lines into a DRAFT purchase order (M2). Sent to a
 * chosen supplier; each line states the ingredient and the quantity to order
 * (typically the suggestion's {@code suggestedQuantity}). The {@code unitCost}
 * is optional and defaults to the ingredient's standard cost in the service.
 * The caller chooses which suggestions to act on, so this only ever drafts the
 * lines it is given.
 */
public record RequestReorderRequest(
        @NotNull UUID supplierId,
        Instant expectedAt,
        @Size(max = 1000) String notes,
        @NotEmpty @Valid List<Line> lines) {

    public record Line(
            @NotNull UUID ingredientId,
            @NotNull @DecimalMin("0.0001") @Digits(integer = 14, fraction = 4) BigDecimal quantity,
            @DecimalMin("0.0000") @Digits(integer = 14, fraction = 4) BigDecimal unitCost) {
    }
}
