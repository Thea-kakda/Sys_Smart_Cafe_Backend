package com.smartcoffee.modules.catalog.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Updates a master customization group (Slice 4A Round 2, §7). Name, limits
 * and order only — code and selection type are immutable. {@code version}
 * echoes the row's optimistic-lock counter (stale = 409
 * CUSTOMIZATION_GROUP_VERSION_CONFLICT). Narrowing the limits is refused
 * (400 MASTER_LIMITS_BREAK_PRODUCT_OVERRIDES) while any product's effective
 * limits would violate the new master range.
 */
public record UpdateCustomizationGroupRequest(
        @NotNull Long version,
        @NotBlank @Size(max = 100) String name,
        @PositiveOrZero int minimumSelections,
        @PositiveOrZero Integer maximumSelections,
        @PositiveOrZero int displayOrder) {
}
