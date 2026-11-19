package com.smartcoffee.modules.catalog.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.infrastructure.storage.ObjectStorageService;
import com.smartcoffee.infrastructure.storage.StorageException;
import com.smartcoffee.infrastructure.storage.StoragePathPolicy;
import com.smartcoffee.infrastructure.storage.StorageProperties;
import com.smartcoffee.modules.audit.application.AuditWriter;
import com.smartcoffee.modules.catalog.api.ManagementCategoryResponse;
import com.smartcoffee.modules.catalog.api.request.CreateCategoryRequest;
import com.smartcoffee.modules.catalog.api.request.UpdateCategoryRequest;
import com.smartcoffee.modules.catalog.domain.ProductCategory;
import com.smartcoffee.modules.catalog.persistence.ProductCategoryRepository;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Docker-free unit coverage of the category write contract: permission,
 * uniqueness, parent reference (existence + self-parent), image-prefix
 * ownership, version conflict and image-replacement cleanup.
 */
class CategoryCommandServiceTest {

    private static final List<String> PREFIXES = List.of(
            "products/", "categories/", "users/", "invoices/", "receipts/", "documents/");

    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final ProductCategoryRepository categories = mock(ProductCategoryRepository.class);
    private final AuditWriter audit = mock(AuditWriter.class);
    private final CatalogImageLifecycle lifecycle = new CatalogImageLifecycle(storage,
            new StoragePathPolicy(new StorageProperties(PREFIXES, List.of("image/jpeg"), 10_485_760L, 900L)));
    private final CatalogManagementMapper mapper =
            new CatalogManagementMapper(key -> key == null ? null : "https://signed/" + key);
    private final CategoryCommandService service = new CategoryCommandService(categories, audit,
            new PermissionEvaluator(), lifecycle, mapper);

    private final UUID actorId = UUID.randomUUID();
    private final CurrentActor manager = new CurrentActor(actorId, "USER",
            Set.of(PermissionCodes.CATEGORY_MANAGE), Set.of(), true);
    private final CurrentActor noPerms = new CurrentActor(actorId, "USER", Set.of("order:read"), Set.of(), true);

    @Test
    void createPersistsAndReturnsSignedImageUrl() {
        when(categories.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        ManagementCategoryResponse resp = service.create(manager,
                new CreateCategoryRequest(null, "DRINKS", "Drinks", "desc", 1, true, "categories/uuid-d.jpg"));

        assertEquals("categories/uuid-d.jpg", resp.imageObjectKey());
        assertEquals("https://signed/categories/uuid-d.jpg", resp.imageUrl());
        verify(audit).success(eq(actorId), any(), eq("CATEGORY_CREATED"), eq("CATALOG"), eq("CATEGORY"),
                any(), any(), any(), any());
    }

    @Test
    void createForbiddenWithoutPermission() {
        assertThrows(ForbiddenException.class,
                () -> service.create(noPerms, new CreateCategoryRequest(null, "DRINKS", "Drinks", null, 1, true, null)));
        verify(categories, never()).saveAndFlush(any());
    }

    @Test
    void createDuplicateCodeRejected() {
        when(categories.existsByCode("DRINKS")).thenReturn(true);
        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.create(manager, new CreateCategoryRequest(null, "DRINKS", "Drinks", null, 1, true, null)));
        assertEquals("DUPLICATE_CODE", ex.code());
    }

    @Test
    void createMissingParentRejected() {
        when(categories.existsById(any())).thenReturn(false);
        ValidationException ex = assertThrows(ValidationException.class, () -> service.create(manager,
                new CreateCategoryRequest(UUID.randomUUID(), "SUB", "Sub", null, 1, true, null)));
        assertEquals("PARENT_NOT_FOUND", ex.code());
    }

    @Test
    void createRejectsWrongImagePrefix() {
        StorageException ex = assertThrows(StorageException.class, () -> service.create(manager,
                new CreateCategoryRequest(null, "DRINKS", "Drinks", null, 1, true, "products/uuid-x.jpg")));
        assertEquals("INVALID_OBJECT_KEY", ex.code());
        verify(categories, never()).saveAndFlush(any());
    }

    @Test
    void updateVersionConflictRejected() {
        ProductCategory existing = ProductCategory.create(null, "DRINKS", "Drinks", null, 1, true, null);
        when(categories.findById(existing.id())).thenReturn(Optional.of(existing));

        ConflictException ex = assertThrows(ConflictException.class, () -> service.update(manager, existing.id(),
                new UpdateCategoryRequest(null, "DRINKS", "Drinks II", null, 1, true, null, 42L)));
        assertEquals("VERSION_CONFLICT", ex.code());
    }

    @Test
    void updateSelfParentRejected() {
        ProductCategory existing = ProductCategory.create(null, "DRINKS", "Drinks", null, 1, true, null);
        when(categories.findById(existing.id())).thenReturn(Optional.of(existing));

        ValidationException ex = assertThrows(ValidationException.class, () -> service.update(manager, existing.id(),
                new UpdateCategoryRequest(existing.id(), "DRINKS", "Drinks", null, 1, true, null,
                        existing.version())));
        assertEquals("INVALID_PARENT", ex.code());
    }

    @Test
    void updateReplacesImageAndDeletesPreviousObject() {
        ProductCategory existing = ProductCategory.create(null, "DRINKS", "Drinks", null, 1, true,
                "categories/old.jpg");
        when(categories.findById(existing.id())).thenReturn(Optional.of(existing));
        when(categories.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        ManagementCategoryResponse resp = service.update(manager, existing.id(),
                new UpdateCategoryRequest(null, "DRINKS", "Drinks", null, 1, true, "categories/new.jpg",
                        existing.version()));

        assertEquals("categories/new.jpg", resp.imageObjectKey());
        verify(storage).delete("categories/old.jpg");
    }
}
