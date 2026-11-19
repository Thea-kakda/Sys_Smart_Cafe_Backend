package com.smartcoffee.modules.catalog.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Creates a category (master data). {@code parentCategoryId} is optional (null =
 * top level); its existence is validated in the application layer. Field ranges
 * mirror the entity schema (code &lt;=50, name &lt;=100). {@code imageObjectKey}
 * is the value returned by {@code POST /api/v1/files/categories} and is checked
 * for {@code categories/} prefix ownership server-side. {@code active} is
 * optional and defaults to true.
 */
public record CreateCategoryRequest(
        UUID parentCategoryId,
        @NotBlank @Size(max = 50) String code,
        @NotBlank @Size(max = 100) String name,
        @Size(max = 5000) String description,
        int displayOrder,
        Boolean active,
        @Size(max = 512) String imageObjectKey) {

    public boolean activeOrDefault() {
        return !Boolean.FALSE.equals(active);
    }
}
