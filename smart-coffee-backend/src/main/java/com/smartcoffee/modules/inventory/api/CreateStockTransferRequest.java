package com.smartcoffee.modules.inventory.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Requests an inter-branch stock transfer (M2). The source and destination
 * stores travel in the body (a transfer is not owned by a single store
 * endpoint); both must be within the actor's scope and must differ, and every
 * line carries a positive base-unit quantity and a non-negative shipped cost.
 * Existence/active checks for ingredients and the source!=dest rule are
 * enforced in the application layer.
 */
public record CreateStockTransferRequest(
        @NotNull UUID sourceStoreId,
        @NotNull UUID destStoreId,
        @Size(max = 1000) String notes,
        @NotEmpty @Valid List<Line> lines) {

    public record Line(
            @NotNull UUID ingredientId,
            @NotNull @DecimalMin("0.0001") @Digits(integer = 14, fraction = 4) BigDecimal quantity,
            @NotNull @DecimalMin("0.0000") @Digits(integer = 14, fraction = 4) BigDecimal unitCost) {
    }
}
