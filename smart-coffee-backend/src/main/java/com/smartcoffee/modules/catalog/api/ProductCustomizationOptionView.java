package com.smartcoffee.modules.catalog.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One option of an attached customization group, as the product editor sees it.
 * Prices and effect numbers stay {@link BigDecimal} so a {@code 0.10} /
 * {@code 1.50} multiplier never degrades into a binary float on the way to the
 * UI; {@code version} is the option's own optimistic-lock token (the same one
 * {@code PUT /management/catalog/customization-options/{id}/inventory-effect}
 * echoes), which is why it is exposed here rather than being re-fetched.
 *
 * <p>Round 1 note: {@code defaultOption} is always {@code false} and
 * {@code numericValue} is always {@code null}. No per-product default column and
 * no generic option numeric column exist in the schema — the only numbers an
 * option carries are {@code priceDelta}, {@code effectQuantity} and
 * {@code effectMultiplier}. Both fields are kept so the Round 2 options editor
 * can bind to a stable shape instead of a renamed contract.
 */
public record ProductCustomizationOptionView(
        UUID optionId,
        String code,
        String name,
        boolean active,
        boolean defaultOption,
        BigDecimal priceDelta,
        int displayOrder,
        BigDecimal numericValue,
        long version,
        InventoryEffectResponse inventoryEffect) {
}
