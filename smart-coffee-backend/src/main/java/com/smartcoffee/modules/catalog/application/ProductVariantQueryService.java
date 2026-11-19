package com.smartcoffee.modules.catalog.application;

import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.modules.catalog.api.ProductVariantResponse;
import com.smartcoffee.modules.catalog.persistence.ProductVariantRepository;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read path for product variants. Requires {@code product:manage} on the
 * management surface (same capability that gates {@code ManagementProductResponse}
 * reads). Public browsing keeps using the base product; variant selection is
 * a Slice 5 concern for POS/Customer once the Dashboard editor is live.
 */
@Service
public class ProductVariantQueryService {

    private final ProductVariantRepository variants;
    private final ProductVariantMapper mapper;
    private final PermissionEvaluator permissions;

    public ProductVariantQueryService(ProductVariantRepository variants, ProductVariantMapper mapper,
            PermissionEvaluator permissions) {
        this.variants = variants;
        this.mapper = mapper;
        this.permissions = permissions;
    }

    @Transactional(readOnly = true)
    public List<ProductVariantResponse> listForProduct(CurrentActor actor, UUID productId) {
        permissions.require(actor, PermissionCodes.PRODUCT_MANAGE);
        return variants.findActiveByProduct(productId).stream().map(mapper::toResponse).toList();
    }

    @Transactional(readOnly = true)
    public ProductVariantResponse get(CurrentActor actor, UUID variantId) {
        permissions.require(actor, PermissionCodes.PRODUCT_MANAGE);
        return variants.findByIdAndDeletedAtIsNull(variantId)
                .map(mapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("VARIANT_NOT_FOUND", "Variant not found"));
    }
}
