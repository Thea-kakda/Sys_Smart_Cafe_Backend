package com.smartcoffee.modules.catalog.application;

import com.smartcoffee.modules.catalog.api.InventoryEffectResponse;
import com.smartcoffee.modules.catalog.api.ProductVariantResponse;
import com.smartcoffee.modules.catalog.domain.CustomizationOption;
import com.smartcoffee.modules.catalog.domain.ProductVariant;
import org.springframework.stereotype.Component;

/**
 * Pure entity-to-DTO projections for the variant + option-effect surface.
 * Kept as a separate mapper (rather than extending
 * {@link CatalogManagementMapper}) so variant concerns stay isolated and the
 * product response does not have to grow variant fields the public browsing
 * path never reads.
 */
@Component
public class ProductVariantMapper {

    public ProductVariantResponse toResponse(ProductVariant v) {
        return new ProductVariantResponse(
                v.id(), v.productId(), v.code(), v.name(), v.description(), v.barcode(),
                v.price(), v.cost(), v.currencyCode(),
                v.isDefault(), v.active(), v.displayOrder(), v.imageObjectKey(),
                v.version(), v.createdAt(), v.updatedAt());
    }

    public InventoryEffectResponse toEffectResponse(CustomizationOption o) {
        return new InventoryEffectResponse(
                o.id(), o.groupId(), o.code(), o.name(), o.additionalPrice(),
                o.inventoryEffectType(), o.sourceIngredientId(), o.targetIngredientId(),
                o.effectQuantity(), o.effectMultiplier(), o.replacesOptionId(),
                o.version());
    }
}
