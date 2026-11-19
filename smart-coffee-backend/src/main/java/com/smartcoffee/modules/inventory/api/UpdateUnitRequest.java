package com.smartcoffee.modules.inventory.api;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/**
 * Full-replacement unit-of-measure update (M2). Carries the optimistic-lock
 * {@code version} echoed from the last read; a stale value maps to
 * {@code 409 VERSION_CONFLICT}. Setting {@code active=false} deactivates (no
 * hard delete).
 */
public record UpdateUnitRequest(
        @NotBlank @Size(max = 20) String code,
        @NotBlank @Size(max = 60) String name,
        @NotBlank @Pattern(regexp = "MASS|VOLUME|COUNT|PACKAGE", message = "unit type must be one of "
                + "MASS/VOLUME/COUNT/PACKAGE") String type,
        @NotNull @Min(0) @Max(6) Integer decimalScale,
        @NotNull @DecimalMin(value = "0.00000001", message = "conversion factor must be positive") BigDecimal factor,
        Boolean baseUnit,
        Boolean active,
        @NotNull @PositiveOrZero Long version) {

    public boolean baseUnitOrFalse() {
        return Boolean.TRUE.equals(baseUnit);
    }

    public boolean activeOrDefault() {
        return !Boolean.FALSE.equals(active);
    }
}
