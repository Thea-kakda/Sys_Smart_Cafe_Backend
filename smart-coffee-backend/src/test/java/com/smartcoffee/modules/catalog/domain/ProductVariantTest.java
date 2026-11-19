package com.smartcoffee.modules.catalog.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Domain factory / mutation guards for {@link ProductVariant}. Referential
 * rules (product existence, currency match against product, one-default-per-
 * product, SKU uniqueness) live in ProductVariantCommandService; only the
 * value-shape checks that the entity itself owns are covered here.
 */
class ProductVariantTest {

    private static final UUID PRODUCT = UUID.randomUUID();

    private static ProductVariant base(String code, BigDecimal price) {
        return ProductVariant.create(PRODUCT, code, "Name", null, null,
                price, null, "USD", false, true, 0, null);
    }

    @Test
    void createRejectsMissingRequiredFields() {
        assertThrows(IllegalArgumentException.class, () ->
                ProductVariant.create(null, "SKU", "Name", null, null,
                        BigDecimal.TEN, null, "USD", false, true, 0, null));
        assertThrows(IllegalArgumentException.class, () ->
                ProductVariant.create(PRODUCT, "", "Name", null, null,
                        BigDecimal.TEN, null, "USD", false, true, 0, null));
        assertThrows(IllegalArgumentException.class, () ->
                ProductVariant.create(PRODUCT, "SKU", "  ", null, null,
                        BigDecimal.TEN, null, "USD", false, true, 0, null));
        assertThrows(IllegalArgumentException.class, () ->
                ProductVariant.create(PRODUCT, "SKU", "Name", null, null,
                        BigDecimal.TEN, null, "", false, true, 0, null));
    }

    @Test
    void createRejectsNegativePriceAndCost() {
        assertThrows(IllegalArgumentException.class, () -> base("SKU", new BigDecimal("-0.01")));
        assertThrows(IllegalArgumentException.class, () ->
                ProductVariant.create(PRODUCT, "SKU", "Name", null, null,
                        BigDecimal.TEN, new BigDecimal("-1"), "USD", false, true, 0, null));
    }

    @Test
    void createTrimsCodeNameAndUppercasesCurrency() {
        var v = ProductVariant.create(PRODUCT, " SKU-01 ", "  Latte ", null, " b1 ",
                new BigDecimal("39.00"), null, "usd", false, true, 0, null);
        assertEquals("SKU-01", v.code());
        assertEquals("Latte", v.name());
        assertEquals("USD", v.currencyCode());
        assertEquals("b1", v.barcode());
    }

    @Test
    void blankBarcodeAndImageNormalizeToNull() {
        var v = ProductVariant.create(PRODUCT, "SKU", "N", null, "   ",
                BigDecimal.TEN, null, "USD", false, true, 0, "");
        assertNull(v.barcode());
        assertNull(v.imageObjectKey());
    }

    @Test
    void updateDetailsAppliesControlledDiff() {
        var v = base("SKU", BigDecimal.TEN);
        long before = v.version();
        v.updateDetails("SKU2", "N2", "desc", "bc", new BigDecimal("42.00"),
                new BigDecimal("10.00"), "VND", 5, "products/x.png");
        assertEquals("SKU2", v.code());
        assertEquals(new BigDecimal("42.00"), v.price());
        assertEquals("VND", v.currencyCode());
        assertEquals(5, v.displayOrder());
        // version field is Hibernate-managed; the domain updateDetails must not
        // touch it directly (so optimistic locking still works at flush).
        assertEquals(before, v.version());
    }

    @Test
    void markDefaultAndActivateFlipFlags() {
        var v = base("SKU", BigDecimal.TEN);
        assertFalse(v.isDefault());
        v.markDefault(true);
        assertTrue(v.isDefault());
        v.activate(false);
        assertFalse(v.active());
    }

    @Test
    void softDeleteStampsDeletedAt() {
        var v = base("SKU", BigDecimal.TEN);
        assertNull(v.deletedAt());
        v.softDelete();
        assertNotNull(v.deletedAt());
    }
}
