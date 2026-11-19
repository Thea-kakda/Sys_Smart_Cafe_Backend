package com.smartcoffee.modules.catalog.api.request;

import jakarta.validation.constraints.NotEmpty;
import java.util.List;
import java.util.UUID;

/**
 * Reorders a product's variants in a single atomic operation. The list must
 * contain every non-deleted variant id belonging to the target product;
 * displayOrder is rewritten to the array index. This exists so the Dashboard
 * editor (Slice 4) can send one request instead of N sequential PATCHes
 * (which would race on the partial unique default-index and produce spurious
 * 409s).
 */
public record ReorderVariantsRequest(@NotEmpty List<UUID> variantIds) {
}
