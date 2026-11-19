package com.smartcoffee.modules.catalog.application;

import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.modules.catalog.api.ManagementCategoryResponse;
import com.smartcoffee.modules.catalog.domain.ProductCategory;
import com.smartcoffee.modules.catalog.persistence.ProductCategoryRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Protected category-management reads for the Dashboard catalog editor. Returns
 * every category (active or not) ordered for editing, exposing the raw
 * {@code imageObjectKey} and optimistic-lock {@code version} alongside a
 * short-lived presigned {@code imageUrl}. Guarded by {@code category:manage}.
 */
@Service
public class CategoryManagementQueryService {

    private final ProductCategoryRepository categories;
    private final CatalogManagementMapper mapper;
    private final PermissionEvaluator permissions;

    public CategoryManagementQueryService(ProductCategoryRepository categories, CatalogManagementMapper mapper,
            PermissionEvaluator permissions) {
        this.categories = categories;
        this.mapper = mapper;
        this.permissions = permissions;
    }

    @Transactional(readOnly = true)
    public List<ManagementCategoryResponse> list(CurrentActor actor) {
        permissions.require(actor, PermissionCodes.CATEGORY_MANAGE);
        List<ProductCategory> rows = categories.findAllByOrderByDisplayOrderAscNameAsc();
        Map<UUID, String> names = parentNames(rows);
        // A top-level category has a null parentCategoryId. The batched parent-name
        // map is empty (Map.of()) in that case, and java.util.Map.of() throws NPE on
        // get(null); mirror the null-parent guard already used in get() so the
        // parent name resolves to null without dereferencing the immutable map.
        return rows.stream()
                .map(c -> mapper.toCategory(c,
                        c.parentCategoryId() == null ? null : names.get(c.parentCategoryId())))
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public ManagementCategoryResponse get(CurrentActor actor, UUID categoryId) {
        permissions.require(actor, PermissionCodes.CATEGORY_MANAGE);
        ProductCategory category = categories.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("CATEGORY_NOT_FOUND", "Category not found"));
        String parentName = category.parentCategoryId() == null ? null
                : categories.findById(category.parentCategoryId()).map(c -> c.name()).orElse(null);
        return mapper.toCategory(category, parentName);
    }

    // JDT false positive: the method-reference receiver (ProductCategory::parentCategoryId)
    // cannot be proven @Nonnull in the default-null perspective; stream elements are never
    // null and the nullable return is filtered below.
    @SuppressWarnings("null")
    private Map<UUID, String> parentNames(List<ProductCategory> rows) {
        List<UUID> ids = rows.stream().map(ProductCategory::parentCategoryId).filter(java.util.Objects::nonNull)
                .distinct().collect(Collectors.toList());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return categories.findAllById(ids).stream()
                .collect(Collectors.toMap(c -> c.id(), c -> c.name(), (a, b) -> a));
    }
}
