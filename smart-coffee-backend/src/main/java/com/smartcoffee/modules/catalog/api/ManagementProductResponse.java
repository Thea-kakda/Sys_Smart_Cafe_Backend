package com.smartcoffee.modules.catalog.api;

import com.smartcoffee.modules.catalog.domain.ProductType;
import com.smartcoffee.modules.catalog.domain.StockMode;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Authoritative product-management row for the Dashboard catalog editor. Unlike
 * the public catalog DTOs it also exposes the raw {@code imageObjectKey} and the
 * optimistic-lock {@code version} so the editor can echo them back on update and
 * show/clear the current image. {@code imageUrl} is a short-lived presigned GET
 * URL resolved by the backend (never persisted); it is null when the product has
 * no image. No bucket name or credential is ever exposed.
 */
public record ManagementProductResponse(
        UUID productId,
        String code,
        UUID categoryId,
        String categoryName,
        String name,
        String description,
        BigDecimal basePrice,
        String currencyCode,
        int preparationMinutes,
        BigDecimal taxRate,
        boolean customizable,
        boolean active,
        ProductType productType,
        StockMode stockMode,
        String imageUrl,
        String imageObjectKey,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
