package com.smartcoffee.modules.inventory.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Creates a DRAFT purchase order for a store against a supplier, with at least
 * one line. {@code supplierId}/ingredient existence and line validation (qty &gt;
 * 0, cost &gt;= 0) are enforced in the application layer; bean validation bounds
 * the payload shape. Quantities are in the ingredient's base unit (matching the
 * balance ledger); {@code expectedAt} is an optional target delivery date.
 */
public record CreatePurchaseOrderRequest(
        @NotNull UUID supplierId,
        Instant expectedAt,
        @Size(max = 1000) String notes,
        @NotEmpty @Valid List<Line> lines) {

    public record Line(
            @NotNull UUID ingredientId,
            @NotNull @DecimalMin("0.0001") @Digits(integer = 14, fraction = 4) BigDecimal quantityOrdered,
            @NotNull @DecimalMin("0.0000") @Digits(integer = 14, fraction = 4) BigDecimal unitCost) {
    }
}
