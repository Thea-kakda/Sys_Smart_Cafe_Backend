package com.smartcoffee.modules.catalog.api.request;

import com.smartcoffee.modules.catalog.domain.SelectionType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

/**
 * Creates a master customization group (Slice 4A Round 2, §7). Codes are
 * globally unique (pre-checked, 409 DUPLICATE_CUSTOMIZATION_GROUP); the
 * selection type is chosen once here and is immutable afterwards, because
 * flipping SINGLE/MULTIPLE would silently invalidate attachments and cart
 * snapshots validated under the old type. A SINGLE group must cap
 * maximumSelections at 1 (enforced in the domain, stable 400).
 */
public record CreateCustomizationGroupRequest(
        @NotBlank @Size(max = 50) String code,
        @NotBlank @Size(max = 100) String name,
        @NotNull SelectionType selectionType,
        @PositiveOrZero int minimumSelections,
        @PositiveOrZero Integer maximumSelections,
        @PositiveOrZero int displayOrder) {
}
