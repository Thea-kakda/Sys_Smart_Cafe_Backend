package com.smartcoffee.modules.catalog.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Creates an option value under one master customization group (Slice 4A
 * Round 2, §7). Codes are unique per group (uk_option_group_code; pre-checked,
 * 409 DUPLICATE_CUSTOMIZATION_OPTION). The inventory effect is NOT part of
 * this contract — it keeps its own dedicated, version-guarded endpoint.
 */
public record CreateCustomizationOptionRequest(
        @NotBlank @Size(max = 50) String code,
        @NotBlank @Size(max = 100) String name,
        @NotNull @PositiveOrZero BigDecimal additionalPrice,
        @PositiveOrZero int displayOrder) {
}
