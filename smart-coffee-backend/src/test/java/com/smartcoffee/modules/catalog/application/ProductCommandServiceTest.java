package com.smartcoffee.modules.catalog.application;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ForbiddenException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.infrastructure.storage.ObjectStorageService;
import com.smartcoffee.infrastructure.storage.StorageException;
import com.smartcoffee.infrastructure.storage.StoragePathPolicy;
import com.smartcoffee.infrastructure.storage.StorageProperties;
import com.smartcoffee.modules.audit.application.AuditWriter;
import com.smartcoffee.modules.catalog.api.ManagementProductResponse;
import com.smartcoffee.modules.catalog.api.request.CreateProductRequest;
import com.smartcoffee.modules.catalog.api.request.UpdateProductRequest;
import com.smartcoffee.modules.catalog.domain.Product;
import com.smartcoffee.modules.catalog.domain.ProductCategory;
import com.smartcoffee.modules.catalog.domain.ProductType;
import com.smartcoffee.modules.catalog.domain.StockMode;
import com.smartcoffee.modules.catalog.persistence.ProductCategoryRepository;
import com.smartcoffee.modules.catalog.persistence.ProductRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Docker-free unit coverage of the product write contract: permission
 * enforcement, referential + uniqueness + precision validation, object-key
 * prefix ownership, optimistic-lock conflict and the create-failure /
 * replacement image-lifecycle compensation. Real {@link PermissionEvaluator},
 * {@link CatalogImageLifecycle} and {@link CatalogManagementMapper} are wired so
 * the shipped logic runs; only the repositories, the audit writer and MinIO are
 * stubbed.
 */
class ProductCommandServiceTest {

    private static final List<String> PREFIXES = List.of(
            "products/", "categories/", "users/", "invoices/", "receipts/", "documents/");

    private final ObjectStorageService storage = mock(ObjectStorageService.class);
    private final ProductRepository products = mock(ProductRepository.class);
    private final ProductCategoryRepository categories = mock(ProductCategoryRepository.class);
    private final AuditWriter audit = mock(AuditWriter.class);
    private final CatalogImageLifecycle lifecycle = new CatalogImageLifecycle(storage,
            new StoragePathPolicy(new StorageProperties(PREFIXES, List.of("image/jpeg"), 10_485_760L, 900L)));
    private final CatalogManagementMapper mapper =
            new CatalogManagementMapper(key -> key == null ? null : "https://signed/" + key);
    private final ProductCommandService service = new ProductCommandService(products, categories, audit,
            new PermissionEvaluator(), lifecycle, mapper);

    private final UUID actorId = UUID.randomUUID();
    private final CurrentActor manager = new CurrentActor(actorId, "USER",
            Set.of(PermissionCodes.PRODUCT_MANAGE), Set.of(), true);
    private final CurrentActor noPerms = new CurrentActor(actorId, "USER", Set.of("order:read"), Set.of(), true);

    private UUID categoryId;

    @BeforeEach
    void seedCategory() {
        ProductCategory coffee = ProductCategory.create(null, "COFFEE", "Coffee", null, 1, true, null);
        categoryId = coffee.id();
        when(categories.existsById(any())).thenReturn(true);
        when(categories.findById(any())).thenReturn(Optional.of(coffee));
    }

    private CreateProductRequest createReq(String imageKey) {
        return new CreateProductRequest("P-100", categoryId, "House Roast", "desc",
                new BigDecimal("12.5000"), "USD", 5, new BigDecimal("0.100000"),
                true, true, imageKey, null, null);
    }

    /** Slice 4A: create payload with an explicit (possibly case-varied) classification. */
    private CreateProductRequest createReq(String imageKey, String type, String mode) {
        return new CreateProductRequest("P-100", categoryId, "House Roast", "desc",
                new BigDecimal("12.5000"), "USD", 5, new BigDecimal("0.100000"),
                true, true, imageKey, type, mode);
    }

    @Test
    void createWithExplicitClassificationPersistsBothEnums() {
        when(products.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        ManagementProductResponse resp = service.create(manager,
                createReq(null, "beverage", "RECIPE_BASED"));

        assertEquals(ProductType.BEVERAGE, resp.productType(), "unknown-case value is accepted");
        assertEquals(StockMode.RECIPE_BASED, resp.stockMode());
    }

    @Test
    void createWithoutClassificationKeepsLegacyDefaults() {
        when(products.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        ManagementProductResponse resp = service.create(manager, createReq(null));

        assertEquals(ProductType.STANDARD, resp.productType(), "omitted fields must not change pre-4A behaviour");
        assertEquals(StockMode.MADE_TO_ORDER, resp.stockMode());
    }

    @Test
    void createRejectsUnknownProductTypeWithStandardValidationCode() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.create(manager, createReq(null, "SMOOTHIE", "MADE_TO_ORDER")));
        assertEquals("INVALID_PRODUCT_TYPE", ex.code());
        verify(products, never()).saveAndFlush(any());
    }

    @Test
    void createRejectsUnknownStockModeWithStandardValidationCode() {
        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.create(manager, createReq(null, "BEVERAGE", "FROZEN")));
        assertEquals("INVALID_STOCK_MODE", ex.code());
        verify(products, never()).saveAndFlush(any());
    }

    @Test
    void updateChangesClassificationAndReturnsServerValues() {
        Product existing = Product.create(categoryId, "P-100", "House Roast", null,
                new BigDecimal("12.5000"), "USD", 5, new BigDecimal("0.1"), true, true, null);
        when(products.findById(existing.id())).thenReturn(Optional.of(existing));
        when(products.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        ManagementProductResponse resp = service.update(manager, existing.id(),
                new UpdateProductRequest("P-100", categoryId, "House Roast", null,
                        new BigDecimal("12.5000"), "USD", 5, new BigDecimal("0.1"), true, true,
                        null, existing.version(), "CAKE", "BATCH_PRODUCTION"));

        assertEquals(ProductType.CAKE, resp.productType());
        assertEquals(StockMode.BATCH_PRODUCTION, resp.stockMode());
    }

    @Test
    void updateOmittingClassificationLeavesCurrentRowUntouched() {
        Product existing = Product.create(categoryId, "P-100", "House Roast", null,
                new BigDecimal("12.5000"), "USD", 5, new BigDecimal("0.1"), true, true, null);
        existing.applyClassification(ProductType.BAKERY, StockMode.FINISHED_GOOD);
        when(products.findById(existing.id())).thenReturn(Optional.of(existing));
        when(products.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        // A rename-only update must not silently reset an already-classified product.
        ManagementProductResponse resp = service.update(manager, existing.id(),
                new UpdateProductRequest("P-100", categoryId, "House Roast II", null,
                        new BigDecimal("12.5000"), "USD", 5, new BigDecimal("0.1"), true, true,
                        null, existing.version(), null, null));

        assertEquals(ProductType.BAKERY, resp.productType());
        assertEquals(StockMode.FINISHED_GOOD, resp.stockMode());
    }

    @Test
    void createPersistsAndReturnsSignedImageUrl() {
        when(products.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        ManagementProductResponse resp = service.create(manager, createReq("products/uuid-latte.jpg"));

        assertEquals("products/uuid-latte.jpg", resp.imageObjectKey());
        assertEquals("https://signed/products/uuid-latte.jpg", resp.imageUrl());
        assertEquals(0L, resp.version());
        verify(audit).success(eq(actorId), any(), eq("PRODUCT_CREATED"), eq("CATALOG"), eq("PRODUCT"),
                any(), any(), any(), any());
    }

    @Test
    void createForbiddenWithoutPermission() {
        assertThrows(ForbiddenException.class, () -> service.create(noPerms, createReq(null)));
        verify(products, never()).saveAndFlush(any());
    }

    @Test
    void createDuplicateCodeRejected() {
        when(products.existsByCode("P-100")).thenReturn(true);
        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.create(manager, createReq(null)));
        assertEquals("DUPLICATE_CODE", ex.code());
        verify(products, never()).saveAndFlush(any());
    }

    @Test
    void createUnknownCategoryRejected() {
        when(categories.existsById(any())).thenReturn(false);
        ValidationException ex = assertThrows(ValidationException.class,
                () -> service.create(manager, createReq(null)));
        assertEquals("CATEGORY_NOT_FOUND", ex.code());
    }

    @Test
    void createRejectsWrongImagePrefixAndDoesNotPersist() {
        StorageException ex = assertThrows(StorageException.class,
                () -> service.create(manager, createReq("users/uuid-avatar.jpg")));
        assertEquals("INVALID_OBJECT_KEY", ex.code());
        verify(products, never()).saveAndFlush(any());
    }

    @Test
    void createRejectsExcessivePriceScale() {
        CreateProductRequest req = new CreateProductRequest("P-101", categoryId, "X", null,
                new BigDecimal("1.00001"), "USD", 1, BigDecimal.ZERO, true, true, null, null, null);
        ValidationException ex = assertThrows(ValidationException.class, () -> service.create(manager, req));
        assertEquals("PRICE_SCALE_EXCEEDED", ex.code());
    }

    @Test
    void createRejectsMalformedCurrency() {
        CreateProductRequest req = new CreateProductRequest("P-102", categoryId, "X", null,
                new BigDecimal("1.0000"), "EU1", 1, BigDecimal.ZERO, true, true, null, null, null);
        ValidationException ex = assertThrows(ValidationException.class, () -> service.create(manager, req));
        assertEquals("INVALID_CURRENCY", ex.code());
    }

    @Test
    void createFailureCompensatesUploadedObject() {
        when(products.saveAndFlush(any())).thenThrow(new RuntimeException("db down"));
        assertThrows(RuntimeException.class, () -> service.create(manager, createReq("products/uuid-latte.jpg")));
        verify(storage).delete("products/uuid-latte.jpg");
        verify(audit, never()).success(any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateVersionConflictRejected() {
        Product existing = Product.create(categoryId, "P-100", "House Roast", null,
                new BigDecimal("12.5000"), "USD", 5, new BigDecimal("0.1"), true, true, "products/old.jpg");
        when(products.findById(existing.id())).thenReturn(Optional.of(existing));

        UpdateProductRequest req = new UpdateProductRequest("P-100", categoryId, "House Roast", null,
                new BigDecimal("13.0000"), "USD", 5, new BigDecimal("0.1"), true, true, "products/old.jpg", 99L,
                        null, null);
        ConflictException ex = assertThrows(ConflictException.class,
                () -> service.update(manager, existing.id(), req));
        assertEquals("VERSION_CONFLICT", ex.code());
    }

    @Test
    void updateMissingProductRejected() {
        UpdateProductRequest req = new UpdateProductRequest("P-999", categoryId, "X", null,
                new BigDecimal("1.0000"), "USD", 1, BigDecimal.ZERO, true, true, null, 0L, null, null);
        assertThrows(ResourceNotFoundException.class, () -> service.update(manager, UUID.randomUUID(), req));
    }

    @Test
    void updateReplacesImageAndDeletesPreviousObject() {
        Product existing = Product.create(categoryId, "P-100", "House Roast", null,
                new BigDecimal("12.5000"), "USD", 5, new BigDecimal("0.1"), true, true, "products/old.jpg");
        when(products.findById(existing.id())).thenReturn(Optional.of(existing));
        when(products.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        // No active transaction in a unit test → post-commit delete runs immediately.
        ManagementProductResponse resp = service.update(manager, existing.id(),
                new UpdateProductRequest("P-100", categoryId, "House Roast II", null,
                        new BigDecimal("13.0000"), "USD", 5, new BigDecimal("0.1"), true, true,
                        "products/new.jpg", existing.version(), null, null));

        assertEquals("products/new.jpg", resp.imageObjectKey());
        verify(storage).delete("products/old.jpg");
        verify(audit).success(eq(actorId), any(), eq("IMAGE_REPLACED"), any(), any(), any(), any(), any(), any());
        verify(audit).success(eq(actorId), any(), eq("PRODUCT_UPDATED"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void updateClearingImageDeletesPreviousObject() {
        Product existing = Product.create(categoryId, "P-100", "House Roast", null,
                new BigDecimal("12.5000"), "USD", 5, new BigDecimal("0.1"), true, true, "products/old.jpg");
        when(products.findById(existing.id())).thenReturn(Optional.of(existing));
        when(products.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        ManagementProductResponse resp = service.update(manager, existing.id(),
                new UpdateProductRequest("P-100", categoryId, "House Roast", null,
                        new BigDecimal("12.5000"), "USD", 5, new BigDecimal("0.1"), true, true,
                        null, existing.version(), null, null));

        assertTrue(resp.imageObjectKey() == null);
        verify(storage).delete("products/old.jpg");
    }

    @Test
    void updateWithoutImageChangeTouchesNoStorage() {
        Product existing = Product.create(categoryId, "P-100", "House Roast", null,
                new BigDecimal("12.5000"), "USD", 5, new BigDecimal("0.1"), true, true, "products/same.jpg");
        when(products.findById(existing.id())).thenReturn(Optional.of(existing));
        when(products.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        service.update(manager, existing.id(),
                new UpdateProductRequest("P-100", categoryId, "Renamed", null,
                        new BigDecimal("12.5000"), "USD", 5, new BigDecimal("0.1"), true, true,
                        "products/same.jpg", existing.version(), null, null));

        verify(storage, never()).delete(any());
    }
}
