package com.smartcoffee.modules.inventory.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure-domain guards for the purchasing aggregates: the PO status machine, the
 * per-line over-receipt guard and the creation validations. No Spring/DB.
 */
class PurchaseOrderDomainTest {

    private static final UUID STORE = UUID.randomUUID();
    private static final UUID SUPPLIER = UUID.randomUUID();
    private static final UUID COFFEE = UUID.randomUUID();
    private static final UUID MILK = UUID.randomUUID();

    private PurchaseOrder draftOrder() {
        return PurchaseOrder.draft(STORE, SUPPLIER, null, "note", UUID.randomUUID(), List.of(
                PurchaseOrderLine.create(COFFEE, new BigDecimal("100"), new BigDecimal("0.0250")),
                PurchaseOrderLine.create(MILK, new BigDecimal("50"), new BigDecimal("0.0015"))));
    }

    @Test
    void startsInDraftAndSubmits() {
        var po = draftOrder();
        assertEquals(PurchaseOrderStatus.DRAFT, po.status());
        assertTrue(po.number().startsWith("PO-"));
        po.submit();
        assertEquals(PurchaseOrderStatus.SUBMITTED, po.status());
        assertTrue(po.isReceivable());
        assertThrows(IllegalStateException.class, po::submit, "cannot submit twice");
    }

    @Test
    void cancelAllowedPreReceiptOnly() {
        var draft = draftOrder();
        draft.cancel();
        assertEquals(PurchaseOrderStatus.CANCELLED, draft.status());

        var submitted = draftOrder();
        submitted.submit();
        submitted.cancel();
        assertEquals(PurchaseOrderStatus.CANCELLED, submitted.status());

        var received = draftOrder();
        received.submit();
        received.lineFor(COFFEE).markReceived(new BigDecimal("100"));
        received.lineFor(MILK).markReceived(new BigDecimal("50"));
        received.applyReceiptTransition();
        assertEquals(PurchaseOrderStatus.RECEIVED, received.status());
        assertThrows(IllegalStateException.class, received::cancel, "cannot cancel a received order");
    }

    @Test
    void receiptTransitionReflectsLineTotals() {
        var po = draftOrder();
        po.submit();
        po.lineFor(COFFEE).markReceived(new BigDecimal("40"));
        po.applyReceiptTransition();
        assertEquals(PurchaseOrderStatus.PARTIALLY_RECEIVED, po.status());
        assertTrue(po.isReceivable());

        po.lineFor(COFFEE).markReceived(new BigDecimal("60"));
        po.lineFor(MILK).markReceived(new BigDecimal("50"));
        po.applyReceiptTransition();
        assertEquals(PurchaseOrderStatus.RECEIVED, po.status());
        assertFalse(po.isReceivable());
    }

    @Test
    void closeOnlyFromReceived() {
        var po = draftOrder();
        assertThrows(IllegalStateException.class, po::close);
        po.submit();
        po.lineFor(COFFEE).markReceived(new BigDecimal("100"));
        po.lineFor(MILK).markReceived(new BigDecimal("50"));
        po.applyReceiptTransition();
        po.close();
        assertEquals(PurchaseOrderStatus.CLOSED, po.status());
    }

    @Test
    void lineGuardRefusesOverReceipt() {
        var line = PurchaseOrderLine.create(COFFEE, new BigDecimal("10"), new BigDecimal("1.0"));
        line.markReceived(new BigDecimal("6"));
        assertEquals(0, new BigDecimal("4").compareTo(line.outstanding()));
        assertThrows(IllegalStateException.class, () -> line.markReceived(new BigDecimal("5")));
        // unchanged after the refused receipt
        assertEquals(0, new BigDecimal("6").compareTo(line.quantityReceived()));
        line.markReceived(new BigDecimal("4"));
        assertTrue(line.fullyReceived());
    }

    @Test
    void creationValidations() {
        assertThrows(IllegalArgumentException.class, () -> PurchaseOrder.draft(STORE, SUPPLIER, null, null,
                UUID.randomUUID(), List.of()), "an order needs at least one line");
        assertThrows(IllegalArgumentException.class,
                () -> PurchaseOrderLine.create(COFFEE, BigDecimal.ZERO, BigDecimal.ONE), "qty must be positive");
        assertThrows(IllegalArgumentException.class,
                () -> PurchaseOrderLine.create(COFFEE, BigDecimal.ONE, new BigDecimal("-0.01")), "cost must be >= 0");
    }
}
