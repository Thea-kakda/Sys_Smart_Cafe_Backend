package com.smartcoffee.modules.inventory.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.modules.inventory.domain.InventoryBatch;
import com.smartcoffee.modules.inventory.domain.StockTransactionLine;
import com.smartcoffee.modules.inventory.persistence.InventoryBatchRepository;
import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/**
 * Pure unit coverage for the FEFO draw (V038, M3): how a required quantity is
 * allocated across the store's lots in expiry order, that each touched lot is
 * drawn down through a guarded decrement carrying its own batch id, and that a
 * shortfall against the unexpired supply aborts rather than half-drawing. The
 * repository is mocked so no database is needed; the ordering itself is a
 * property of the FEFO query exercised in BatchFefoIT.
 */
class FefoConsumptionServiceTest {

    private static final UUID STORE = UUID.randomUUID();
    private static final UUID ING = UUID.randomUUID();

    private InventoryBatch lot(String lotNumber, String expiryOffsetDays, String qty, String cost) {
        LocalDate expiry = expiryOffsetDays == null ? null : LocalDate.now().plusDays(Long.parseLong(expiryOffsetDays));
        return InventoryBatch.open(STORE, ING, lotNumber, expiry, new BigDecimal(qty), new BigDecimal(cost));
    }

    @Test
    void drawsEarliestLotsFirstAcrossABatchSpanningRequest() {
        InventoryBatchRepository repo = mock(InventoryBatchRepository.class);
        InventoryBatch first = lot("LOT-1", "10", "40", "0.05");
        InventoryBatch second = lot("LOT-2", "30", "30", "0.06");
        when(repo.pickFefoForUpdate(STORE, ING)).thenReturn(List.of(first, second));
        when(repo.decrementIfEnough(any(), any())).thenReturn(1);

        var svc = new FefoConsumptionService(repo);
        List<StockTransactionLine> lines = svc.drawLines(STORE, ING, new BigDecimal("50"), new BigDecimal("70"));

        assertEquals(2, lines.size(), "spans the first lot and part of the second");
        assertEquals(0, new BigDecimal("-40").compareTo(delta(lines.get(0))));
        assertEquals(0, new BigDecimal("-10").compareTo(delta(lines.get(1))));
        // Running balance threads 70 -> 30 -> 20.
        assertEquals(0, new BigDecimal("30").compareTo(after(lines.get(0))));
        assertEquals(0, new BigDecimal("20").compareTo(after(lines.get(1))));
        // Each line carries the lot it drew from.
        assertEquals(first.id(), batchId(lines.get(0)));
        assertEquals(second.id(), batchId(lines.get(1)));
        verify(repo).decrementIfEnough(first.id(), new BigDecimal("40"));
        verify(repo).decrementIfEnough(second.id(), new BigDecimal("10"));
    }

    @Test
    void shortageAgainstUnexpiredSupplyAborts() {
        InventoryBatchRepository repo = mock(InventoryBatchRepository.class);
        when(repo.pickFefoForUpdate(STORE, ING)).thenReturn(List.of(lot("LOT-1", "10", "10", "0.05")));
        when(repo.decrementIfEnough(any(), any())).thenReturn(1);

        var svc = new FefoConsumptionService(repo);
        ConflictException short_ = assertThrows(ConflictException.class,
                () -> svc.drawLines(STORE, ING, new BigDecimal("50"), new BigDecimal("10")));
        assertEquals("INSUFFICIENT_STOCK", short_.code());
    }

    @Test
    void concurrentDrawRaceStopsTheWholeAllocation() {
        InventoryBatchRepository repo = mock(InventoryBatchRepository.class);
        InventoryBatch only = lot("LOT-1", "10", "50", "0.05");
        when(repo.pickFefoForUpdate(STORE, ING)).thenReturn(List.of(only));
        when(repo.decrementIfEnough(eq(only.id()), any())).thenReturn(0); // lost the race

        var svc = new FefoConsumptionService(repo);
        assertThrows(ConflictException.class, () -> svc.drawLines(STORE, ING, new BigDecimal("20"), new BigDecimal("50")));
    }

    @Test
    void nonPositiveQuantityDrawsNothing() {
        InventoryBatchRepository repo = mock(InventoryBatchRepository.class);
        var svc = new FefoConsumptionService(repo);
        assertTrue(svc.drawLines(STORE, ING, BigDecimal.ZERO, new BigDecimal("10")).isEmpty());
        verify(repo, never()).pickFefoForUpdate(any(), any());
        verify(repo, times(0)).decrementIfEnough(any(), any());
    }

    private static BigDecimal delta(StockTransactionLine l) {
        return read(l, "delta");
    }

    private static BigDecimal after(StockTransactionLine l) {
        return read(l, "after");
    }

    private static UUID batchId(StockTransactionLine l) {
        return (UUID) readObj(l, "batchId");
    }

    private static BigDecimal read(StockTransactionLine l, String name) {
        return (BigDecimal) readObj(l, name);
    }

    private static Object readObj(StockTransactionLine l, String name) {
        try {
            Field f = StockTransactionLine.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(l);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
