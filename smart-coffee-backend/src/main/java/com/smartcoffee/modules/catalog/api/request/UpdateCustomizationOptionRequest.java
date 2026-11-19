package com.smartcoffee.modules.catalog.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Updates an option value in place (Slice 4A Round 2, §7): name, price delta
 * and order. Code and group membership are immutable; the effect payload is
 * untouched (separate endpoint). {@code version} echoes the row's lock token
 * (stale = 409 CUSTOMIZATION_OPTION_VERSION_CONFLICT).
 */
public record UpdateCustomizationOptionRequest(
        @NotNull Long version,
        @NotBlank @Size(max = 100) String name,
        @NotNull @PositiveOrZero BigDecimal additionalPrice,
        @PositiveOrZero int displayOrder) {
}
