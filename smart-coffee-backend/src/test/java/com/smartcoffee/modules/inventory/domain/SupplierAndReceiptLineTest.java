package com.smartcoffee.modules.inventory.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Pure-domain validations for the supplier master + goods-receipt line entities. */
class SupplierAndReceiptLineTest {

    @Test
    void createTrimsAndBlanksToNull() {
        var s = Supplier.create("  ACME  ", "  Acme Foods  ", "   ", "012345", "a@b.c", "", "TAX1", true);
        assertEquals("ACME", s.code());
        assertEquals("Acme Foods", s.name());
        assertNull(s.contactName(), "blank -> null");
        assertNull(s.address(), "empty -> null");
        assertEquals("012345", s.phone());
        assertTrue(s.active());
    }

    @Test
    void createRejectsBlankCodeOrName() {
        assertThrows(IllegalArgumentException.class,
                () -> Supplier.create(" ", "Name", null, null, null, null, null, true));
        assertThrows(IllegalArgumentException.class,
                () -> Supplier.create("CODE", "  ", null, null, null, null, null, true));
    }

    @Test
    void updateDetailsReplacesFields() {
        var s = Supplier.create("ACME", "Acme Foods", null, null, null, null, null, true);
        s.updateDetails("ACME2", "Acme Ltd", "Jane", "999", "j@b.c", "Road 1", "TAX2", false);
        assertEquals("ACME2", s.code());
        assertEquals("Acme Ltd", s.name());
        assertEquals("Jane", s.contactName());
        assertFalse(s.active());
    }

    @Test
    void receiptLineValidatesQuantityAndCost() {
        var id = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class,
                () -> GoodsReceiptLine.create(id, java.math.BigDecimal.ZERO, java.math.BigDecimal.ONE, null, null));
        assertThrows(IllegalArgumentException.class,
                () -> GoodsReceiptLine.create(id, java.math.BigDecimal.ONE, new java.math.BigDecimal("-1"), null, null));
        var ok = GoodsReceiptLine.create(id, java.math.BigDecimal.ONE, java.math.BigDecimal.ONE, "LOT-9",
                LocalDate.parse("2030-01-01"));
        assertEquals("LOT-9", ok.lotNumber());
        assertEquals(LocalDate.parse("2030-01-01"), ok.expiryDate());
    }
}
