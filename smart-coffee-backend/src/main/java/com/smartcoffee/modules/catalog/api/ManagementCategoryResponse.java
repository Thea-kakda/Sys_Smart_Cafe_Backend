package com.smartcoffee.modules.catalog.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Authoritative category-management row for the Dashboard catalog editor.
 * Exposes the raw {@code imageObjectKey} and optimistic-lock {@code version} in
 * addition to a resolved short-lived {@code imageUrl} (null when no image).
 * {@code parentName} is resolved for display only. No bucket or credential is
 * ever exposed.
 */
public record ManagementCategoryResponse(
        UUID categoryId,
        UUID parentCategoryId,
        String parentName,
        String code,
        String name,
        String description,
        String imageUrl,
        String imageObjectKey,
        int displayOrder,
        boolean active,
        long version,
        Instant createdAt,
        Instant updatedAt) {
}
