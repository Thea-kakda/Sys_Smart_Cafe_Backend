package com.smartcoffee.modules.inventory.domain;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure-domain guards for the inter-branch transfer aggregate: the status machine
 * (REQUESTED -> IN_TRANSIT -> RECEIVED, cancellation only pre-dispatch), the
 * distinct-stores rule and the line creation validations. No Spring/DB.
 */
class StockTransferDomainTest {

    private static final UUID SOURCE = UUID.randomUUID();
    private static final UUID DEST = UUID.randomUUID();
    private static final UUID COFFEE = UUID.randomUUID();
    private static final UUID MILK = UUID.randomUUID();

    private StockTransfer requested() {
        return StockTransfer.request(SOURCE, DEST, "note", UUID.randomUUID(), List.of(
                StockTransferLine.create(COFFEE, new BigDecimal("30"), new BigDecimal("0.05")),
                StockTransferLine.create(MILK, new BigDecimal("10"), new BigDecimal("0.01"))));
    }

    @Test
    void startsRequestedAndFlowsForward() {
        var t = requested();
        assertEquals(StockTransferStatus.REQUESTED, t.status());
        assertTrue(t.number().startsWith("TR-"));

        t.dispatch();
        assertEquals(StockTransferStatus.IN_TRANSIT, t.status());
        assertNotNull(t.dispatchedAt());

        t.receive();
        assertEquals(StockTransferStatus.RECEIVED, t.status());
        assertNotNull(t.receivedAt());
    }

    @Test
    void cannotReceiveBeforeDispatchAndCannotDispatchTwice() {
        var t = requested();
        assertThrows(IllegalStateException.class, t::receive, "must dispatch first");
        t.dispatch();
        assertThrows(IllegalStateException.class, t::dispatch, "cannot dispatch twice");
    }

    @Test
    void cancelAllowedPreReceiptInMachine() {
        // The domain machine permits cancelling any unreceived transfer
        // (REQUESTED or IN_TRANSIT); the strand-protection that refuses to
        // cancel an already-dispatched one is a service-level rule, covered in
        // StockTransferIT. Here we only assert RECEIVED cannot be cancelled.
        var t = requested();
        t.cancel();
        assertEquals(StockTransferStatus.CANCELLED, t.status());

        var shipped = requested();
        shipped.dispatch();
        shipped.cancel();
        assertEquals(StockTransferStatus.CANCELLED, shipped.status());

        var arrived = requested();
        arrived.dispatch();
        arrived.receive();
        assertThrows(IllegalStateException.class, arrived::cancel, "received cannot be cancelled");
    }

    @Test
    void creationValidations() {
        assertThrows(IllegalArgumentException.class, () -> StockTransfer.request(SOURCE, SOURCE, null,
                UUID.randomUUID(), List.of(StockTransferLine.create(COFFEE, BigDecimal.ONE, BigDecimal.ONE))),
                "source and dest must differ");
        assertThrows(IllegalArgumentException.class, () -> StockTransfer.request(SOURCE, DEST, null,
                UUID.randomUUID(), List.of()), "needs at least one line");
        assertThrows(IllegalArgumentException.class,
                () -> StockTransferLine.create(COFFEE, BigDecimal.ZERO, BigDecimal.ONE), "qty must be positive");
        assertThrows(IllegalArgumentException.class,
                () -> StockTransferLine.create(COFFEE, BigDecimal.ONE, new BigDecimal("-0.01")),
                "cost must be zero or positive");
    }
}
