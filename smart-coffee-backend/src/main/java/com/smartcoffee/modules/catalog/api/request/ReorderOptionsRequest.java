package com.smartcoffee.modules.catalog.api.request;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;

/**
 * Reorders one group's options in a single atomic operation (Slice 4A Round 2,
 * §7). The list must contain every option id of the target group (inactive
 * included); displayOrder is rewritten to the array index. Mirrors the variant
 * reorder so the Dashboard can send one request instead of N PATCHes racing on
 * per-row versions.
 */
public record ReorderOptionsRequest(@NotEmpty List<UUID> optionIds) {
}
