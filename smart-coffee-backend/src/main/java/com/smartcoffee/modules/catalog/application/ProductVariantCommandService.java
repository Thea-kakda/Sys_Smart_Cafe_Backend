package com.smartcoffee.modules.catalog.application;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.modules.catalog.api.ProductVariantResponse;
import com.smartcoffee.modules.catalog.api.request.CreateProductVariantRequest;
import com.smartcoffee.modules.catalog.api.request.UpdateProductVariantRequest;
import com.smartcoffee.modules.catalog.domain.Product;
import com.smartcoffee.modules.catalog.domain.ProductVariant;
import com.smartcoffee.modules.catalog.persistence.ProductRepository;
import com.smartcoffee.modules.catalog.persistence.ProductVariantRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Write path for product variants. Enforces, in this order:
 *
 * <ol>
 *   <li>{@code variant:manage} permission (service-layer, never UI-only).</li>
 *   <li>Parent product existence (404 PRODUCT_NOT_FOUND).</li>
 *   <li>Field shape via {@link ProductVariant#create} / {@link
 *       ProductVariant#updateDetails}.</li>
 *   <li>Currency match against the parent product (400 CURRENCY_MISMATCH).</li>
 *   <li>SKU uniqueness within the product (409 DUPLICATE_VARIANT_SKU; DB
 *       partial unique index + saveAndFlush covers the concurrent-create race).</li>
 *   <li>Exactly one active default per product (partial unique index
 *       {@code uk_variant_one_default_per_product} + transactional pre-clear in
 *       {@link #setDefault}).</li>
 *   <li>Optimistic-lock version echo on every mutation (409 PRODUCT_VERSION_CONFLICT).</li>
 *   <li>Refuse soft-deleting the current default (409 DEFAULT_VARIANT_REQUIRED)
 *       so callers must pick another first.</li>
 * </ol>
 *
 * Updates use a controlled in-place diff (never delete-all-and-reinsert) so
 * variantId stays stable for existing order references.
 */
@Service
public class ProductVariantCommandService {

    private final ProductVariantRepository variants;
    private final ProductRepository products;
    private final PermissionEvaluator permissions;
    private final ProductVariantMapper mapper;

    public ProductVariantCommandService(ProductVariantRepository variants, ProductRepository products,
            PermissionEvaluator permissions, ProductVariantMapper mapper) {
        this.variants = variants;
        this.products = products;
        this.permissions = permissions;
        this.mapper = mapper;
    }

    @Transactional
    public ProductVariantResponse create(CurrentActor actor, UUID productId, CreateProductVariantRequest req) {
        permissions.require(actor, PermissionCodes.VARIANT_MANAGE);
        Product product = requireProduct(productId);
        validateShape(req.price(), req.cost(), req.currencyCode(), product);
        requireUniqueCode(productId, req.code(), null);

        boolean wantsDefault = req.isDefaultOrFalse();
        // If this variant is created as the default and one already exists,
        // demote the current default first (in the same tx) so the partial
        // unique index uk_variant_one_default_per_product cannot collide.
        if (wantsDefault) {
            variants.findActiveDefault(productId).ifPresent(existing -> {
                existing.markDefault(false);
                variants.saveAndFlush(existing);
            });
        }

        ProductVariant v = ProductVariant.create(productId, req.code(), req.name(), req.description(),
                req.barcode(), req.price(), req.cost(), req.currencyCode(),
                wantsDefault, req.activeOrDefault(), req.displayOrder(), req.imageObjectKey());
        try {
            variants.saveAndFlush(v);
        } catch (DataIntegrityViolationException race) {
            throw new ConflictException("DUPLICATE_VARIANT_SKU",
                    "Variant code already exists for this product: " + req.code());
        }
        return mapper.toResponse(v);
    }

    @Transactional
    public ProductVariantResponse update(CurrentActor actor, UUID variantId, UpdateProductVariantRequest req) {
        permissions.require(actor, PermissionCodes.VARIANT_MANAGE);
        ProductVariant v = requireVariant(variantId);
        if (!Objects.equals(req.version(), v.version())) {
            throw new ConflictException("PRODUCT_VERSION_CONFLICT",
                    "This variant was updated by another user. Refresh before editing again.");
        }
        Product product = requireProduct(v.productId());
        validateShape(req.price(), req.cost(), req.currencyCode(), product);
        requireUniqueCode(v.productId(), req.code(), variantId);

        v.updateDetails(req.code(), req.name(), req.description(), req.barcode(),
                req.price(), req.cost(), req.currencyCode(), req.displayOrder(), req.imageObjectKey());
        try {
            variants.saveAndFlush(v);
        } catch (DataIntegrityViolationException race) {
            throw new ConflictException("DUPLICATE_VARIANT_SKU",
                    "Variant code already exists for this product: " + req.code());
        }
        return mapper.toResponse(v);
    }

    @Transactional
    public ProductVariantResponse setActive(CurrentActor actor, UUID variantId, boolean active) {
        permissions.require(actor, PermissionCodes.VARIANT_MANAGE);
        ProductVariant v = requireVariant(variantId);
        if (!active && v.isDefault()) {
            // Refuse to deactivate the product's current default. Callers must
            // setDefault on a sibling first so the invariant (a product with
            // active variants has exactly one active default) is preserved.
            long siblings = variants.findActiveByProduct(v.productId()).stream()
                    .filter(x -> !x.id().equals(v.id())).count();
            if (siblings > 0) {
                throw new ConflictException("DEFAULT_VARIANT_REQUIRED",
                        "Choose another default variant before deactivating this one.");
            }
        }
        v.activate(active);
        variants.saveAndFlush(v);
        return mapper.toResponse(v);
    }

    /**
     * Atomically flips the default flag for a product: demotes the current
     * default (if any and different) then promotes the target. Guarded so a
     * retry with the same target is idempotent, and so the target must be
     * active to become default (inactive variants stay historical).
     */
    @Transactional
    public ProductVariantResponse setDefault(CurrentActor actor, UUID variantId) {
        permissions.require(actor, PermissionCodes.VARIANT_MANAGE);
        ProductVariant target = requireVariant(variantId);
        if (!target.active()) {
            throw new ValidationException("INVALID_VARIANT_STATE", "Only an active variant can be the default.");
        }
        variants.findActiveDefault(target.productId()).ifPresent(existing -> {
            if (!Objects.equals(existing.id(), target.id())) {
                existing.markDefault(false);
                variants.saveAndFlush(existing);
            }
        });
        if (!target.isDefault()) {
            target.markDefault(true);
            try {
                variants.saveAndFlush(target);
            } catch (DataIntegrityViolationException race) {
                throw new ConflictException("MULTIPLE_DEFAULT_VARIANTS",
                        "Another concurrent request already set a different default. Refresh and retry.");
            }
        }
        return mapper.toResponse(target);
    }

    /**
     * Rewrites displayOrder atomically. The variantIds list must contain every
     * non-deleted variant of the product so a partial reorder cannot leave
     * duplicate orderings behind.
     */
    @Transactional
    public List<ProductVariantResponse> reorder(CurrentActor actor, UUID productId, List<UUID> variantIds) {
        permissions.require(actor, PermissionCodes.VARIANT_MANAGE);
        requireProduct(productId);
        List<ProductVariant> current = variants.findActiveByProduct(productId);
        if (current.size() != variantIds.size()) {
            throw new ValidationException("REORDER_LIST_MISMATCH",
                    "Reorder must list every non-deleted variant exactly once.");
        }
        for (ProductVariant v : current) {
            int index = variantIds.indexOf(v.id());
            if (index < 0) {
                throw new ValidationException("REORDER_LIST_MISMATCH",
                        "Variant " + v.id() + " does not belong to this product.");
            }
            v.updateDetails(v.code(), v.name(), v.description(), v.barcode(),
                    v.price(), v.cost(), v.currencyCode(), index, v.imageObjectKey());
        }
        variants.saveAllAndFlush(current);
        return current.stream().map(mapper::toResponse).toList();
    }

    /**
     * Soft delete (deletedAt = now). Historical order references remain
     * readable because the row itself is preserved. The current default cannot
     * be deleted without picking another first.
     */
    @Transactional
    public void delete(CurrentActor actor, UUID variantId) {
        permissions.require(actor, PermissionCodes.VARIANT_MANAGE);
        ProductVariant v = requireVariant(variantId);
        if (v.isDefault()) {
            long remaining = variants.findActiveByProduct(v.productId()).stream()
                    .filter(x -> !x.id().equals(v.id())).count();
            if (remaining > 0) {
                throw new ConflictException("DEFAULT_VARIANT_REQUIRED",
                        "Choose another default variant before deleting this one.");
            }
        }
        v.softDelete();
        variants.saveAndFlush(v);
    }

    // ------------------------------------------------------------- guards

    private Product requireProduct(UUID productId) {
        return products.findById(productId)
                .orElseThrow(() -> new ResourceNotFoundException("PRODUCT_NOT_FOUND", "Product not found"));
    }

    private ProductVariant requireVariant(UUID variantId) {
        return variants.findByIdAndDeletedAtIsNull(variantId)
                .orElseThrow(() -> new ResourceNotFoundException("VARIANT_NOT_FOUND", "Variant not found"));
    }

    private void validateShape(BigDecimal price, BigDecimal cost, String currency, Product product) {
        if (price == null || price.signum() < 0) {
            throw new ValidationException("INVALID_VARIANT_PRICE", "Variant price must be non-negative");
        }
        if (price.scale() > 4 || price.precision() > 14) {
            throw new ValidationException("INVALID_VARIANT_PRICE",
                    "Variant price exceeds supported precision numeric(14,4)");
        }
        if (cost != null && cost.signum() < 0) {
            throw new ValidationException("INVALID_VARIANT_PRICE", "Variant cost must be non-negative");
        }
        if (currency == null || !currency.equalsIgnoreCase(product.currencyCode())) {
            throw new ValidationException("CURRENCY_MISMATCH",
                    "Variant currency must match the product currency: " + product.currencyCode());
        }
    }

    private void requireUniqueCode(UUID productId, String code, UUID selfId) {
        String trimmed = code == null ? "" : code.trim();
        if (selfId == null) {
            if (variants.existsByProductIdAndCodeAndDeletedAtIsNull(productId, trimmed)) {
                throw new ConflictException("DUPLICATE_VARIANT_SKU",
                        "Variant code already exists for this product: " + trimmed);
            }
            return;
        }
        boolean clash = variants.findActiveByProduct(productId).stream()
                .anyMatch(v -> !v.id().equals(selfId) && v.code().equalsIgnoreCase(trimmed));
        if (clash) {
            throw new ConflictException("DUPLICATE_VARIANT_SKU",
                    "Variant code already exists for this product: " + trimmed);
        }
    }
}
