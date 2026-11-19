package com.smartcoffee.modules.catalog.api.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Creates a catalog product (master data). Field ranges mirror the entity schema
 * (code &lt;=50, name &lt;=150, currency exactly 3). {@code imageObjectKey} is the
 * value returned by {@code POST /api/v1/files/products} and is validated in the
 * application layer for {@code products/} prefix ownership; a signed URL, bucket
 * name or credential is never accepted here. {@code active}/{@code customizable}
 * are optional and default to true/false.
 *
 * <p>Slice 4A adds the optional classification pair {@code productType} /
 * {@code stockMode}. They arrive as free-form strings on purpose: the service
 * parses them against the domain enums and answers with the standard
 * {@code 400 INVALID_PRODUCT_TYPE} / {@code 400 INVALID_STOCK_MODE} validation
 * contract, instead of letting Jackson fail the whole body as an unparseable
 * message. Matching is case-insensitive and trimmed. A legacy caller that omits
 * both fields keeps the pre-4A behaviour exactly: {@code STANDARD} +
 * {@code MADE_TO_ORDER} — the V039 column defaults — so no existing product is
 * reclassified, forced to have a Recipe, or made unavailable by this change.
 */
public record CreateProductRequest(
        @NotBlank @Size(max = 50) String code,
        @NotNull UUID categoryId,
        @NotBlank @Size(max = 150) String name,
        @Size(max = 5000) String description,
        @NotNull @PositiveOrZero BigDecimal basePrice,
        @NotBlank @Size(min = 3, max = 3) String currencyCode,
        @PositiveOrZero int preparationMinutes,
        @NotNull @DecimalMin("0") BigDecimal taxRate,
        Boolean customizable,
        Boolean active,
        @Size(max = 512) String imageObjectKey,
        @Size(max = 20) String productType,
        @Size(max = 20) String stockMode) {

    public boolean activeOrDefault() {
        return !Boolean.FALSE.equals(active);
    }

    public boolean customizableOrDefault() {
        return Boolean.TRUE.equals(customizable);
    }
}
