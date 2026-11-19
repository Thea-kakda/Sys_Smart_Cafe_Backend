package com.smartcoffee.modules.cart.domain;

import static org.junit.jupiter.api.Assertions.*;

import com.smartcoffee.common.exception.ValidationException;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Slice 4B.1 unit tests — the cart line's authoritative pricing rule.
 *
 * <p>The variant price REPLACES the store/product base (it is never added to
 * it), option deltas add on top of the variant price, and quantity multiplies
 * the final unit price. Variant identity always travels with the line: a
 * variant→none reconfiguration must clear the snapshot so a product-level line
 * can never display a stale variant label.
 */
class CartItemVariantPricingTest {

    private static final UUID PRODUCT = UUID.randomUUID();
    private static final UUID VARIANT = UUID.randomUUID();
    private static final BigDecimal PRODUCT_BASE = new BigDecimal("3.5000");
    private static final BigDecimal VARIANT_PRICE = new BigDecimal("4.5000");

    private static CartItem.VariantSnapshot large() {
        return new CartItem.VariantSnapshot("LATTE-LARGE-16OZ", "Large 16 oz", VARIANT_PRICE);
    }

    private static List<CartItemCustomization> extraShot() {
        return List.of(new CartItemCustomization(UUID.randomUUID(), UUID.randomUUID(), "Extras", "Extra Shot",
                new BigDecimal("0.1000"), 1));
    }

    @Test
    void noVariantLineUsesTheStoreProductEffectivePrice() {
        var item = CartItem.create(PRODUCT, null, null, "P1", "Latte", 1, PRODUCT_BASE, null, List.of());
        assertEquals(0, PRODUCT_BASE.compareTo(item.unitBasePrice()));
        assertNull(item.variantId());
        assertNull(item.variantName());
        assertNull(item.variantPrice());
    }

    @Test
    void variantPriceReplacesTheProductBaseItIsNeverAddedToIt() {
        var item = CartItem.create(PRODUCT, VARIANT, large(), "P1", "Latte", 1, VARIANT_PRICE, null, List.of());
        assertEquals(0, VARIANT_PRICE.compareTo(item.unitBasePrice()),
                "base must be the variant price alone (4.50), not variant + product (8.00)");
        assertEquals(0, new BigDecimal("4.5000").compareTo(item.lineTotal()));
        assertEquals("LATTE-LARGE-16OZ", item.variantCode());
        assertEquals("Large 16 oz", item.variantName());
        assertEquals(0, VARIANT_PRICE.compareTo(item.variantPrice()));
    }

    @Test
    void optionDeltaAddsOnTopOfTheVariantPrice() {
        var item = CartItem.create(PRODUCT, VARIANT, large(), "P1", "Latte", 1, VARIANT_PRICE, null, extraShot());
        assertEquals(0, new BigDecimal("0.1000").compareTo(item.unitCustomizationPrice()));
        assertEquals(0, new BigDecimal("4.6000").compareTo(item.lineTotal()), "4.50 + 0.10");
    }

    @Test
    void quantityMultipliesTheFinalAuthoritativeUnitPrice() {
        var item = CartItem.create(PRODUCT, VARIANT, large(), "P1", "Latte", 2, VARIANT_PRICE, null, extraShot());
        assertEquals(0, new BigDecimal("9.2000").compareTo(item.lineTotal()), "(4.50 + 0.10) * 2");
    }

    @Test
    void reconfigurationToAVariantSnapshotsIdentityAndBaseTogether() {
        var item = CartItem.create(PRODUCT, null, null, "P1", "Latte", 1, PRODUCT_BASE, null, List.of());
        item.applyConfiguration(VARIANT, large(), VARIANT_PRICE, List.of());
        assertEquals(0, VARIANT_PRICE.compareTo(item.unitBasePrice()));
        assertEquals("Large 16 oz", item.variantName());
        assertEquals(0, new BigDecimal("4.5000").compareTo(item.lineTotal()));
    }

    @Test
    void reconfigurationAwayFromAVariantClearsTheSnapshot() {
        var item = CartItem.create(PRODUCT, VARIANT, large(), "P1", "Latte", 1, VARIANT_PRICE, null, List.of());
        item.applyConfiguration(null, null, PRODUCT_BASE, List.of());
        assertNull(item.variantId());
        assertNull(item.variantCode());
        assertNull(item.variantName());
        assertNull(item.variantPrice());
        assertEquals(0, PRODUCT_BASE.compareTo(item.unitBasePrice()));
    }

    @Test
    void nonPositiveQuantityIsRejected() {
        assertThrows(ValidationException.class,
                () -> CartItem.create(PRODUCT, VARIANT, large(), "P1", "Latte", 0, VARIANT_PRICE, null, List.of()));
    }

    @Test
    void productWithoutVariantBehaviorIsUnchanged() {
        var item = CartItem.create(PRODUCT, "P1", "Latte", 3, PRODUCT_BASE, "note", List.of());
        assertEquals(0, new BigDecimal("10.5000").compareTo(item.lineTotal()), "legacy 7-arg factory intact");
        assertEquals(0, PRODUCT_BASE.compareTo(item.unitBasePrice()));
        assertNull(item.variantId());
    }
}
