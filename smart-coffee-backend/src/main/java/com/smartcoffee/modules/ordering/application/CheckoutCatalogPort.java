package com.smartcoffee.modules.ordering.application;

import java.math.BigDecimal;
import java.util.*;

public interface CheckoutCatalogPort {
    /**
     * Re-validates a cart line against the live catalog at checkout. Slice 4B.1:
     * the variant the cart line was priced and reserved with travels along, so
     * the resolved base price is the authoritative VARIANT price — a checkout
     * that ignored it would fail its own PRICE_CHANGED guard on every variant
     * line. The 4-arg form stays for product-level validation.
     */
    default ValidatedItem validate(UUID storeId, UUID productId, int quantity, List<SelectedOption> options) {
        return validate(storeId, productId, null, quantity, options);
    }

    ValidatedItem validate(UUID storeId, UUID productId, UUID variantId, int quantity, List<SelectedOption> options);

    /**
     * Authoritative product → category map for promotion targeting (Phase B2).
     * Checkout never accepts a category from the client.
     */
    Map<UUID, UUID> productCategoryIds(Collection<UUID> productIds);

    record SelectedOption(UUID groupId, UUID optionId, int quantity) {
    }

    record ValidatedOption(UUID groupId, UUID optionId, String groupName, String optionName, BigDecimal price,
            int quantity) {
    }

    // variantId/variantCode/variantName are the resolved variant identity (all
    // null for a product-level line); basePrice already carries the variant
    // price when a variant was validated.
    record ValidatedItem(UUID productId, String code, String name, String stationCode, BigDecimal basePrice,
            BigDecimal customizationPrice, BigDecimal taxRate, String currency, UUID variantId, String variantCode,
            String variantName, List<ValidatedOption> options) {
    }
}

