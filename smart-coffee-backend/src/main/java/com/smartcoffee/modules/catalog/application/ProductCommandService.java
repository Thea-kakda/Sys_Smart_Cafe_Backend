package com.smartcoffee.modules.catalog.application;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.modules.catalog.api.ManagementProductResponse;
import com.smartcoffee.modules.catalog.api.request.CreateProductRequest;
import com.smartcoffee.modules.catalog.api.request.UpdateProductRequest;
import com.smartcoffee.modules.catalog.domain.Product;
import com.smartcoffee.modules.catalog.domain.ProductType;
import com.smartcoffee.modules.catalog.domain.StockMode;
import com.smartcoffee.modules.catalog.persistence.ProductCategoryRepository;
import com.smartcoffee.modules.catalog.persistence.ProductRepository;
import com.smartcoffee.modules.file.application.FileCategory;
import com.smartcoffee.modules.audit.application.AuditWriter;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Product master-data write path (create / update) with MinIO image
 * integration. Authorization is enforced here (service-layer convention) so the
 * behaviour is unit-testable and cannot be bypassed by any caller. Invariants:
 *
 * <ul>
 *   <li>{@code product:manage} permission required for every mutation;</li>
 *   <li>referenced {@code categoryId} must exist; {@code code} is unique —
 *       both pre-checked and mapped to {@code 409 DUPLICATE_CODE} (the
 *       {@code saveAndFlush} {@link DataIntegrityViolationException} covers the
 *       concurrent-create race);</li>
 *   <li>price/tax precision is validated against the column definitions
 *       (numeric(14,4) / numeric(9,6)) so oversized values fail with a clean
 *       400 instead of a truncation or a DB error;</li>
 *   <li>the image object key is validated for the {@code products/} prefix, and
 *       the storage lifecycle is compensated around the transaction via
 *       {@link CatalogImageLifecycle} (delete the new object if the create
 *       rolls back; delete the replaced object only after a successful commit);</li>
 *   <li>updates echo {@code version} or map to {@code 409 VERSION_CONFLICT};</li>
 *   <li>every mutation writes an audit record with before/after snapshots.</li>
 * </ul>
 */
@Service
public class ProductCommandService {

    private static final String IMAGE_PREFIX = FileCategory.PRODUCTS.prefix();

    private final ProductRepository products;
    private final ProductCategoryRepository categories;
    private final AuditWriter audit;
    private final PermissionEvaluator permissions;
    private final CatalogImageLifecycle imageLifecycle;
    private final CatalogManagementMapper mapper;

    public ProductCommandService(ProductRepository products, ProductCategoryRepository categories,
            AuditWriter audit, PermissionEvaluator permissions, CatalogImageLifecycle imageLifecycle,
            CatalogManagementMapper mapper) {
        this.products = products;
        this.categories = categories;
        this.audit = audit;
        this.permissions = permissions;
        this.imageLifecycle = imageLifecycle;
        this.mapper = mapper;
    }

    @Transactional
    public ManagementProductResponse create(CurrentActor actor, CreateProductRequest req) {
        permissions.require(actor, PermissionCodes.PRODUCT_MANAGE);

        String imageKey = imageLifecycle.validate(req.imageObjectKey(), IMAGE_PREFIX);
        requireCategory(req.categoryId());
        requireCodeUnique(req.code(), null);
        validatePrice(req.basePrice());
        validateTax(req.taxRate());
        validateCurrency(req.currencyCode());

        Product product = Product.create(req.categoryId(), req.code(), req.name(), req.description(),
                req.basePrice(), req.currencyCode(), req.preparationMinutes(), req.taxRate(),
                req.customizableOrDefault(), req.activeOrDefault(), imageKey);
        // Slice 4A: optional classification. Omitted halves keep the entity
        // default (STANDARD / MADE_TO_ORDER), so a legacy payload produces the
        // exact row it produced before this slice existed.
        applyClassification(product, req.productType(), req.stockMode());
        try {
            products.saveAndFlush(product);
        } catch (DataIntegrityViolationException concurrentCreate) {
            // Lost the code-uniqueness race — the new object is now unreferenced.
            imageLifecycle.compensateCreate(imageKey);
            throw new ConflictException("DUPLICATE_CODE", "Product code already exists: " + req.code());
        } catch (RuntimeException ex) {
            // Any other persistence failure must not leave the upload orphaned.
            imageLifecycle.compensateCreate(imageKey);
            throw ex;
        }

        ManagementProductResponse after = toResponse(product);
        audit.success(actor.userId(), null, "PRODUCT_CREATED", "CATALOG", "PRODUCT", product.id(),
                null, after, "Created product " + product.code());
        return after;
    }

    @Transactional
    public ManagementProductResponse update(CurrentActor actor, UUID productId, UpdateProductRequest req) {
        permissions.require(actor, PermissionCodes.PRODUCT_MANAGE);

        Product product = products.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("PRODUCT_NOT_FOUND", "Product not found"));
        if (!Objects.equals(req.version(), product.version())) {
            throw new ConflictException("VERSION_CONFLICT",
                    "This product was updated by another user. Refresh before editing again.");
        }

        String newKey = imageLifecycle.validate(req.imageObjectKey(), IMAGE_PREFIX);
        requireCategory(req.categoryId());
        requireCodeUnique(req.code(), productId);
        validatePrice(req.basePrice());
        validateTax(req.taxRate());
        validateCurrency(req.currencyCode());

        ManagementProductResponse before = toResponse(product);
        String oldKey = product.imageObjectKey();

        product.updateDetails(req.categoryId(), req.code(), req.name(), req.description(),
                req.basePrice(), req.currencyCode(), req.preparationMinutes(), req.taxRate(),
                req.customizableOrDefault(), req.activeOrDefault());
        // Slice 4A: a null/blank classification half means "leave as it is", so an
        // editor that has not loaded the classification cannot accidentally reset
        // an existing product to STANDARD / MADE_TO_ORDER.
        applyClassification(product, req.productType(), req.stockMode());
        boolean imageChanged = !Objects.equals(oldKey, newKey);
        if (imageChanged) {
            product.changeImage(newKey);
        }

        try {
            products.saveAndFlush(product);
        } catch (DataIntegrityViolationException concurrentUpdate) {
            throw new ConflictException("DUPLICATE_CODE", "Product code already exists: " + req.code());
        }

        if (imageChanged) {
            // Reclaim the previous object only once the swap is committed.
            imageLifecycle.deleteAfterCommit(oldKey, newKey);
            audit.success(actor.userId(), null, "IMAGE_REPLACED", "CATALOG", "PRODUCT", productId,
                    Objects.toString(oldKey, null), Objects.toString(newKey, null),
                    newKey == null ? "Product image removed" : "Product image replaced");
        }

        ManagementProductResponse after = toResponse(product);
        audit.success(actor.userId(), null, "PRODUCT_UPDATED", "CATALOG", "PRODUCT", productId,
                before, after, "Updated product " + product.code());
        return after;
    }

    // ------------------------------------------------------------- guards

    /**
     * Resolves the optional Slice 4A classification pair. A blank half falls back
     * to whatever the product already carries (the entity default on create), so
     * omitting the fields is a true no-op. An unknown value is a 400 with the
     * accepted set, matching the V039 CHECK constraints — never a 500 and never a
     * silently coerced default.
     */
    private void applyClassification(Product product, String rawType, String rawMode) {
        ProductType type = isBlank(rawType) ? product.productType() : parseType(rawType);
        StockMode mode = isBlank(rawMode) ? product.stockMode() : parseMode(rawMode);
        if (type != product.productType() || mode != product.stockMode()) {
            product.applyClassification(type, mode);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private ProductType parseType(String raw) {
        try {
            return ProductType.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            throw new ValidationException("INVALID_PRODUCT_TYPE",
                    "productType must be one of " + Arrays.toString(ProductType.values()));
        }
    }

    private StockMode parseMode(String raw) {
        try {
            return StockMode.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException unknown) {
            throw new ValidationException("INVALID_STOCK_MODE",
                    "stockMode must be one of " + Arrays.toString(StockMode.values()));
        }
    }

    private void requireCategory(UUID categoryId) {
        if (categoryId == null || !categories.existsById(categoryId)) {
            throw new ValidationException("CATEGORY_NOT_FOUND", "Category does not exist");
        }
    }

    private void requireCodeUnique(String code, UUID selfId) {
        String trimmed = code == null ? "" : code.trim();
        boolean clash = selfId == null ? products.existsByCode(trimmed)
                : products.existsByCodeAndIdNot(trimmed, selfId);
        if (clash) {
            throw new ConflictException("DUPLICATE_CODE", "Product code already exists: " + trimmed);
        }
    }

    private void validatePrice(BigDecimal value) {
        if (value == null) {
            throw new ValidationException("PRICE_REQUIRED", "Base price is required");
        }
        if (value.signum() < 0) {
            throw new ValidationException("NEGATIVE_PRICE", "Base price must not be negative");
        }
        if (value.scale() > 4 || value.precision() > 14) {
            throw new ValidationException("PRICE_SCALE_EXCEEDED",
                    "Base price exceeds supported precision numeric(14,4)");
        }
    }

    private void validateTax(BigDecimal value) {
        if (value == null) {
            throw new ValidationException("TAX_REQUIRED", "Tax rate is required");
        }
        if (value.signum() < 0) {
            throw new ValidationException("NEGATIVE_TAX", "Tax rate must not be negative");
        }
        if (value.scale() > 6 || value.precision() > 9) {
            throw new ValidationException("TAX_SCALE_EXCEEDED",
                    "Tax rate exceeds supported precision numeric(9,6)");
        }
    }

    private void validateCurrency(String currencyCode) {
        String code = currencyCode == null ? "" : currencyCode.trim();
        if (code.length() != 3 || !code.matches("[A-Za-z]{3}")) {
            throw new ValidationException("INVALID_CURRENCY", "Currency code must be a 3-letter ISO code");
        }
    }

    private ManagementProductResponse toResponse(Product product) {
        String categoryName = categories.findById(product.categoryId())
                .map(c -> c.name()).orElse(null);
        return mapper.toProduct(product, categoryName);
    }
}
