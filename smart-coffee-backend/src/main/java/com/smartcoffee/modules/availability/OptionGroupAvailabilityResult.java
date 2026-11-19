package com.smartcoffee.modules.availability;

import java.util.List;
import java.util.UUID;

/**
 * Availability of ONE option group and its values (Slice 3A, spec §4/§10). Immutable.
 *
 * <p>{@code required} is derived from the group's minimum selections (>= 1).
 * {@code atLeastOneValidSelection} reports whether the group is satisfiable right
 * now — i.e. there exists at least one value whose {@code selectable && available}
 * holds (spec §10 required-group rule). A required group that is not satisfiable is
 * what makes a Variant/Product un-orderable, carrying
 * {@link AvailabilityReason#REQUIRED_OPTION_GROUP_UNAVAILABLE}.
 */
public record OptionGroupAvailabilityResult(
        UUID groupId,
        String groupName,
        String selectionType,
        boolean required,
        int minimumSelections,
        Integer maximumSelections,
        boolean atLeastOneValidSelection,
        List<OptionAvailabilityResult> values) {

    public OptionGroupAvailabilityResult {
        values = List.copyOf(values);
    }
}
