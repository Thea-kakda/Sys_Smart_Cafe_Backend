package com.smartcoffee.modules.inventory.api;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;

/**
 * Creates a unit of measure (M2). {@code type} is one of MASS/VOLUME/COUNT/
 * PACKAGE (ck_unit_type). Decimal scale is bounded 0..6 and the conversion
 * factor to base must be positive (ck_unit_scale/ck_unit_factor) — mirrored in
 * the domain. A base unit has factor 1 by convention. {@code active} defaults
 * to true.
 */
public record CreateUnitRequest(
        @NotBlank @Size(max = 20) String code,
        @NotBlank @Size(max = 60) String name,
        @NotBlank @Pattern(regexp = "MASS|VOLUME|COUNT|PACKAGE", message = "unit type must be one of "
                + "MASS/VOLUME/COUNT/PACKAGE") String type,
        @NotNull @Min(0) @Max(6) Integer decimalScale,
        @NotNull @DecimalMin(value = "0.00000001", message = "conversion factor must be positive") BigDecimal factor,
        Boolean baseUnit,
        Boolean active) {

    public boolean baseUnitOrFalse() {
        return Boolean.TRUE.equals(baseUnit);
    }

    public boolean activeOrDefault() {
        return !Boolean.FALSE.equals(active);
    }
}
