package com.smartcoffee.modules.catalog.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/**
 * Creates a product variant. Variant codes (SKUs) are unique per product
 * (uk_variant_code_per_product); the service pre-checks and maps a clash to
 * 409 DUPLICATE_VARIANT_SKU. {@code currencyCode} MUST match the parent
 * product's currency (enforced in the service, not the DB, so we can return a
 * stable business error code). {@code active}/{@code isDefault} default to
 * true/false; a product's first variant is commonly the default.
 */
public record CreateProductVariantRequest(
        @NotBlank @Size(max = 80) String code,
        @NotBlank @Size(max = 150) String name,
        @Size(max = 5000) String description,
        @Size(max = 64) String barcode,
        @NotNull @PositiveOrZero BigDecimal price,
        @PositiveOrZero BigDecimal cost,
        @NotBlank @Size(min = 3, max = 3) String currencyCode,
        Boolean isDefault,
        Boolean active,
        @PositiveOrZero int displayOrder,
        @Size(max = 512) String imageObjectKey) {

    public boolean isDefaultOrFalse() {
        return Boolean.TRUE.equals(isDefault);
    }

    public boolean activeOrDefault() {
        return !Boolean.FALSE.equals(active);
    }
}
