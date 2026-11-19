package com.smartcoffee.modules.inventory.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Goods receipt against a purchase order. Each line states the ingredient and
 * the physical quantity received now (partial receipts allowed; the cumulative
 * total may not exceed the ordered quantity — enforced per PO line). A null
 * {@code unitCost} falls back to the PO line's unit cost when restating the
 * moving average. {@code lotNumber}/{@code expiryDate} are captured for M3 batch
 * tracking but are optional for untracked ingredients.
 */
public record ReceiveGoodsRequest(
        @NotEmpty @Valid List<Line> lines) {

    public record Line(
            @NotNull UUID ingredientId,
            @NotNull @DecimalMin("0.0001") @Digits(integer = 14, fraction = 4) BigDecimal quantity,
            @DecimalMin("0.0000") @Digits(integer = 14, fraction = 4) BigDecimal unitCost,
            @Size(max = 60) String lotNumber,
            LocalDate expiryDate) {
    }
}
