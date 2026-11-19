package com.smartcoffee.modules.catalog.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Read projection of a {@code ProductVariant}. Exposes the raw
 * {@code imageObjectKey} and optimistic-lock {@code version} the public
 * catalog DTOs omit; the Dashboard editor (Slice 4) echoes version back on
 * update. Soft-deleted variants are never mapped.
 */
public record ProductVariantResponse(
        UUID variantId,
        UUID productId,
        String code,
        String name,
        String description,
        String barcode,
        BigDecimal price,
        BigDecimal cost,
        String currencyCode,
        boolean isDefault,
        boolean active,
        int displayOrder,
        String imageObjectKey,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
