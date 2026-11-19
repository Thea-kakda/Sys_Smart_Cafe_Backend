package com.smartcoffee.modules.catalog.api;

import java.util.List;
import java.util.UUID;

/**
 * Availability-aware customization GROUP for the channel catalog (Slice 3B, spec §9).
 * {@code atLeastOneValidSelection} reports whether the group is currently satisfiable
 * (some value with {@code selectable && available}); for a required group this is what
 * gates product orderability. Presentation metadata (code, name, selection bounds) is
 * taken from the catalog; the per-value availability is merged from the Slice 3A
 * option results — never recomputed here.
 */
public record CatalogOptionGroupResponse(
        UUID groupId,
        String code,
        String name,
        boolean required,
        String selectionType,
        int minimumSelections,
        Integer maximumSelections,
        boolean atLeastOneValidSelection,
        List<CatalogOptionValueResponse> values) {

    public CatalogOptionGroupResponse {
        values = List.copyOf(values);
    }
}
