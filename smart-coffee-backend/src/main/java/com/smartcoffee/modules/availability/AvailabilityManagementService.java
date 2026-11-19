package com.smartcoffee.modules.availability;

import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.common.security.StoreAuthorizationService;
import com.smartcoffee.modules.catalog.domain.ProductVariant;
import com.smartcoffee.modules.catalog.persistence.ProductVariantRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Authorization + projection wrapper around the pure {@link AvailabilityService}
 * for the management preview API (Slice 3A, spec §18). The compute engine itself
 * takes no {@link CurrentActor}; this service enforces the three gates the spec
 * requires — product/catalog view, inventory view and store scope — BEFORE any
 * calculation, then optionally narrows the immutable result for the caller.
 *
 * <p>It remains strictly READ-ONLY: every path runs under
 * {@code @Transactional(readOnly = true)} and only ever filters/copy-folds the
 * engine's records; no branch here touches balances, reservations, the ledger,
 * Cart, Order or reconciliation state.
 */
@Service
public class AvailabilityManagementService {

    private final AvailabilityService availability;
    private final ProductVariantRepository variants;
    private final PermissionEvaluator permissions;
    private final StoreAuthorizationService stores;

    public AvailabilityManagementService(AvailabilityService availability, ProductVariantRepository variants,
            PermissionEvaluator permissions, StoreAuthorizationService stores) {
        this.availability = availability;
        this.variants = variants;
        this.permissions = permissions;
        this.stores = stores;
    }

    /**
     * Management preview of one product's availability at one store (§18). Optional
     * parameters: {@code variantId} narrows the variant detail (validated against the
     * product), {@code includeOptions=false} drops the option-group breakdown. The
     * product-level verdict (orderable / inStock / servings) is always the whole-product
     * computation — filtering a variant never rewrites the aggregate.
     */
    @Transactional(readOnly = true)
    public ProductAvailabilityResult preview(CurrentActor actor, UUID storeId, UUID productId, UUID variantId,
            boolean includeOptions, int quantity) {
        permissions.require(actor, PermissionCodes.BRANCH_PRODUCT_READ);
        permissions.require(actor, PermissionCodes.INVENTORY_READ);
        stores.requireStore(actor, storeId);

        ProductAvailabilityResult result = availability.availabilityForProduct(storeId, productId, quantity);

        if (variantId != null) {
            result = filterVariant(result, productId, variantId);
        }
        if (!includeOptions) {
            result = withoutOptions(result);
        }
        return result;
    }

    /** The internal batch API (§19) with the same authorization gates, store-scoped. */
    @Transactional(readOnly = true)
    public List<ProductAvailabilityResult> previewBatch(CurrentActor actor, UUID storeId, List<UUID> productIds,
            int quantity) {
        permissions.require(actor, PermissionCodes.BRANCH_PRODUCT_READ);
        permissions.require(actor, PermissionCodes.INVENTORY_READ);
        stores.requireStore(actor, storeId);
        return availability.availabilityForProducts(storeId, productIds, quantity);
    }

    private ProductAvailabilityResult filterVariant(ProductAvailabilityResult result, UUID productId, UUID variantId) {
        ProductVariant variant = variants.findByIdAndDeletedAtIsNull(variantId)
                .orElseThrow(() -> new ResourceNotFoundException("VARIANT_NOT_FOUND", "Variant not found"));
        if (!variant.productId().equals(productId)) {
            throw new ValidationException("VARIANT_PRODUCT_MISMATCH", "Variant does not belong to this product");
        }
        List<VariantAvailabilityResult> kept = result.variants().stream()
                .filter(v -> variantId.equals(v.variantId())).toList();
        return copyWith(result, kept, result.optionGroups());
    }

    private ProductAvailabilityResult withoutOptions(ProductAvailabilityResult result) {
        return copyWith(result, result.variants(), List.of());
    }

    private static ProductAvailabilityResult copyWith(ProductAvailabilityResult r,
            List<VariantAvailabilityResult> variants, List<OptionGroupAvailabilityResult> optionGroups) {
        return new ProductAvailabilityResult(r.productId(), r.storeId(), r.active(), r.listed(), r.orderable(),
                r.inStock(), r.manualState(), r.availableBaseServings(), r.reasonCodes(), variants, optionGroups,
                r.calculatedAt(), r.availabilityFingerprint());
    }
}
