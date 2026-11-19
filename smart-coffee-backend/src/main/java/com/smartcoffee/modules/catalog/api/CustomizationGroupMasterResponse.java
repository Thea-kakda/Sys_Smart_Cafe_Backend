package com.smartcoffee.modules.catalog.api;

import java.util.List;
import java.util.UUID;

/**
 * One master customization group with its options for the Dashboard Options tab
 * (Slice 4A Round 2, §7). Inactive groups and options are included and flagged:
 * the editor must be able to re-activate or see what products still attach.
 * {@code selectionType} is reported but immutable after creation (see the
 * domain note in {@code CustomizationGroup.create}).
 */
public record CustomizationGroupMasterResponse(
        UUID groupId,
        String code,
        String name,
        String selectionType,
        int minimumSelections,
        Integer maximumSelections,
        int displayOrder,
        boolean active,
        long version,
        List<CustomizationOptionMasterResponse> options) {
}
