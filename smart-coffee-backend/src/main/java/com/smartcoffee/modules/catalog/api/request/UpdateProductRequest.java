package com.smartcoffee.modules.catalog.api.request;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Full-replacement product update (master data). Mirrors
 * {@link CreateProductRequest} plus the optimistic-lock {@code version}, which
 * the client must echo from the last management read; a stale value maps to
 * {@code 409 VERSION_CONFLICT}. {@code imageObjectKey} is the value returned by
 * {@code POST /api/v1/files/products}; {@code null}/blank clears the image (the
 * old object is deleted after the update commits), while a changed key swaps the
 * image. Prefix ownership ({@code products/}) is validated in the application
 * layer — a signed URL, bucket name or credential is never accepted here.
 *
 * <p>Slice 4A adds the optional classification pair {@code productType} /
 * {@code stockMode}, parsed by the service against the domain enums (case
 * insensitive) with the standard {@code 400 INVALID_PRODUCT_TYPE} /
 * {@code 400 INVALID_STOCK_MODE} contract. They are deliberately NOT required:
 * a caller that omits them keeps the product's current classification, so the
 * existing update contract stays backward compatible.
 */
public record UpdateProductRequest(
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
        @NotNull @PositiveOrZero Long version,
        @Size(max = 20) String productType,
        @Size(max = 20) String stockMode) {

    public boolean activeOrDefault() {
        return !Boolean.FALSE.equals(active);
    }

    public boolean customizableOrDefault() {
        return Boolean.TRUE.equals(customizable);
    }
}
