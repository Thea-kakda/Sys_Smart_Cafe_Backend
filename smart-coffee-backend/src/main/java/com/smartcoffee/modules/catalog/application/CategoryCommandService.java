package com.smartcoffee.modules.catalog.application;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.modules.catalog.api.ManagementCategoryResponse;
import com.smartcoffee.modules.catalog.api.request.CreateCategoryRequest;
import com.smartcoffee.modules.catalog.api.request.UpdateCategoryRequest;
import com.smartcoffee.modules.catalog.domain.ProductCategory;
import com.smartcoffee.modules.catalog.persistence.ProductCategoryRepository;
import com.smartcoffee.modules.file.application.FileCategory;
import com.smartcoffee.modules.audit.application.AuditWriter;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Category master-data write path (create / update) with MinIO image
 * integration. Mirrors {@link ProductCommandService}: {@code category:manage}
 * is enforced here, {@code code} uniqueness and the parent reference are
 * validated, the image key is bound to the {@code categories/} prefix, and the
 * object lifecycle is compensated around the transaction. No hard delete is
 * offered — categories are retired via {@code active=false}.
 */
@Service
public class CategoryCommandService {

    private static final String IMAGE_PREFIX = FileCategory.CATEGORIES.prefix();

    private final ProductCategoryRepository categories;
    private final AuditWriter audit;
    private final PermissionEvaluator permissions;
    private final CatalogImageLifecycle imageLifecycle;
    private final CatalogManagementMapper mapper;

    public CategoryCommandService(ProductCategoryRepository categories, AuditWriter audit,
            PermissionEvaluator permissions, CatalogImageLifecycle imageLifecycle,
            CatalogManagementMapper mapper) {
        this.categories = categories;
        this.audit = audit;
        this.permissions = permissions;
        this.imageLifecycle = imageLifecycle;
        this.mapper = mapper;
    }

    @Transactional
    public ManagementCategoryResponse create(CurrentActor actor, CreateCategoryRequest req) {
        permissions.require(actor, PermissionCodes.CATEGORY_MANAGE);

        String imageKey = imageLifecycle.validate(req.imageObjectKey(), IMAGE_PREFIX);
        requireParent(req.parentCategoryId(), null);
        requireCodeUnique(req.code(), null);

        ProductCategory category = ProductCategory.create(req.parentCategoryId(), req.code(), req.name(),
                req.description(), req.displayOrder(), req.activeOrDefault(), imageKey);
        try {
            categories.saveAndFlush(category);
        } catch (DataIntegrityViolationException concurrentCreate) {
            imageLifecycle.compensateCreate(imageKey);
            throw new ConflictException("DUPLICATE_CODE", "Category code already exists: " + req.code());
        } catch (RuntimeException ex) {
            imageLifecycle.compensateCreate(imageKey);
            throw ex;
        }

        ManagementCategoryResponse after = toResponse(category);
        audit.success(actor.userId(), null, "CATEGORY_CREATED", "CATALOG", "CATEGORY", category.id(),
                null, after, "Created category " + category.code());
        return after;
    }

    @Transactional
    public ManagementCategoryResponse update(CurrentActor actor, UUID categoryId, UpdateCategoryRequest req) {
        permissions.require(actor, PermissionCodes.CATEGORY_MANAGE);

        ProductCategory category = categories.findById(categoryId)
                .orElseThrow(() -> new ResourceNotFoundException("CATEGORY_NOT_FOUND", "Category not found"));
        if (!Objects.equals(req.version(), category.version())) {
            throw new ConflictException("VERSION_CONFLICT",
                    "This category was updated by another user. Refresh before editing again.");
        }

        String newKey = imageLifecycle.validate(req.imageObjectKey(), IMAGE_PREFIX);
        requireParent(req.parentCategoryId(), categoryId);
        requireCodeUnique(req.code(), categoryId);

        ManagementCategoryResponse before = toResponse(category);
        String oldKey = category.imageObjectKey();

        category.updateDetails(req.parentCategoryId(), req.code(), req.name(), req.description(),
                req.displayOrder(), req.activeOrDefault());
        boolean imageChanged = !Objects.equals(oldKey, newKey);
        if (imageChanged) {
            category.changeImage(newKey);
        }

        try {
            categories.saveAndFlush(category);
        } catch (DataIntegrityViolationException concurrentUpdate) {
            throw new ConflictException("DUPLICATE_CODE", "Category code already exists: " + req.code());
        }

        if (imageChanged) {
            imageLifecycle.deleteAfterCommit(oldKey, newKey);
            audit.success(actor.userId(), null, "IMAGE_REPLACED", "CATALOG", "CATEGORY", categoryId,
                    Objects.toString(oldKey, null), Objects.toString(newKey, null),
                    newKey == null ? "Category image removed" : "Category image replaced");
        }

        ManagementCategoryResponse after = toResponse(category);
        audit.success(actor.userId(), null, "CATEGORY_UPDATED", "CATALOG", "CATEGORY", categoryId,
                before, after, "Updated category " + category.code());
        return after;
    }

    // ------------------------------------------------------------- guards

    private void requireParent(UUID parentCategoryId, UUID selfId) {
        if (parentCategoryId == null) {
            return;
        }
        if (parentCategoryId.equals(selfId)) {
            throw new ValidationException("INVALID_PARENT", "A category cannot be its own parent");
        }
        if (!categories.existsById(parentCategoryId)) {
            throw new ValidationException("PARENT_NOT_FOUND", "Parent category does not exist");
        }
    }

    private void requireCodeUnique(String code, UUID selfId) {
        String trimmed = code == null ? "" : code.trim();
        boolean clash = selfId == null ? categories.existsByCode(trimmed)
                : categories.existsByCodeAndIdNot(trimmed, selfId);
        if (clash) {
            throw new ConflictException("DUPLICATE_CODE", "Category code already exists: " + trimmed);
        }
    }

    private ManagementCategoryResponse toResponse(ProductCategory category) {
        String parentName = category.parentCategoryId() == null ? null
                : categories.findById(category.parentCategoryId()).map(c -> c.name()).orElse(null);
        return mapper.toCategory(category, parentName);
    }
}
