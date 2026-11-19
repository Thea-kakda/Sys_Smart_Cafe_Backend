package com.smartcoffee.modules.catalog.api.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * Full-replacement category update (master data). Mirrors
 * {@link CreateCategoryRequest} plus the optimistic-lock {@code version}, which
 * the client must echo from the last management read; a stale value maps to
 * {@code 409 VERSION_CONFLICT}. {@code parentCategoryId} null clears the parent
 * (moves to top level); self-parenting is rejected in the domain.
 * {@code imageObjectKey} null/blank clears the image (old object deleted after
 * commit), a changed key swaps it. Prefix ownership ({@code categories/}) is
 * validated in the application layer.
 */
public record UpdateCategoryRequest(
        UUID parentCategoryId,
        @NotBlank @Size(max = 50) String code,
        @NotBlank @Size(max = 100) String name,
        @Size(max = 5000) String description,
        int displayOrder,
        Boolean active,
        @Size(max = 512) String imageObjectKey,
        @NotNull @PositiveOrZero Long version) {

    public boolean activeOrDefault() {
        return !Boolean.FALSE.equals(active);
    }
}
