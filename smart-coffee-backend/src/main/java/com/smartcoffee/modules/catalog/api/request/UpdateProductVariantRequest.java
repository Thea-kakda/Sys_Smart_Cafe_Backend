package com.smartcoffee.modules.catalog.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Update path for a product variant. Uses controlled diff (never delete +
 * reinsert): every mutable field is provided in-place, and {@code version}
 * echoes the current row's optimistic-lock counter. A stale version maps to
 * 409 PRODUCT_VERSION_CONFLICT (variant-scoped: code VARIANTS_VERSION_CONFLICT
 * is used if the target is a variant, PRODUCT_VERSION_CONFLICT if the caller
 * is editing the product parent). Default flag changes go through a dedicated
 * endpoint so we can enforce the "one default per product" invariant atomically.
 */
public record UpdateProductVariantRequest(
        @NotNull Long version,
        @NotBlank @Size(max = 80) String code,
        @NotBlank @Size(max = 150) String name,
        @Size(max = 5000) String description,
        @Size(max = 64) String barcode,
        @NotNull @PositiveOrZero BigDecimal price,
        @PositiveOrZero BigDecimal cost,
        @NotBlank @Size(min = 3, max = 3) String currencyCode,
        @PositiveOrZero int displayOrder,
        @Size(max = 512) String imageObjectKey) {
}
