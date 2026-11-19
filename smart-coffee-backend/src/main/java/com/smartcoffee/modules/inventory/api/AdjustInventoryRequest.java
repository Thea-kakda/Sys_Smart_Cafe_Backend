package com.smartcoffee.modules.inventory.api;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Manual stock adjustment command (Full-stack wiring §1.5). One ingredient,
 * one direction, mandatory reason. {@code quantity} is positive in both
 * directions (direction carries the sign) and is expressed in the
 * ingredient's BASE unit — the same unit balances are held in.
 * {@code unitCost} is optional and only meaningful for IN: when present it
 * restates the moving average cost to the given value (delivery/stock-in
 * revaluation). {@code type} defaults to ADJUSTMENT; STOCK_IN / RETURN /
 * DAMAGED classify the physical cause on the ledger row.
 */
public record AdjustInventoryRequest(@NotNull UUID ingredientId, @NotNull @Pattern(regexp = "IN|OUT") String direction,
        @NotNull @DecimalMin(value = "0.0001") @Digits(integer = 14, fraction = 4) BigDecimal quantity,
        @NotBlank @Size(max = 500) String reason, @DecimalMin("0.0000") BigDecimal unitCost,
        @Pattern(regexp = "ADJUSTMENT|STOCK_IN|RETURN|DAMAGED") String type) {
}
