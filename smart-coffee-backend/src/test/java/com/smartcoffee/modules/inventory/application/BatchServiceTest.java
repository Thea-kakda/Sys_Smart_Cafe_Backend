package com.smartcoffee.modules.inventory.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartcoffee.modules.inventory.domain.Ingredient;
import com.smartcoffee.modules.inventory.domain.InventoryBatch;
import com.smartcoffee.modules.inventory.persistence.BatchQueryRepository;
import com.smartcoffee.modules.inventory.persistence.InventoryBalanceRepository;
import com.smartcoffee.modules.inventory.persistence.InventoryBatchRepository;
import com.smartcoffee.modules.inventory.persistence.StockTransactionRepository;
import com.smartcoffee.modules.audit.application.AuditWriter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Pure unit coverage for the batch stock-in hook (V038, M3): a receipt with a
 * lot number opens a fresh lot when none exists, merges into the existing lot
 * when the same number arrives again, defaults an un-lotted receipt to the
 * UNLOTTED sentinel, and derives a lot expiry from the ingredient's shelf life
 * when the receipt carries none. The repositories are mocked — no database.
 */
class BatchServiceTest {

    private static final UUID STORE = UUID.randomUUID();

    private BatchService service(InventoryBatchRepository repo) {
        return new BatchService(repo, mock(BatchQueryRepository.class), mock(InventoryBalanceRepository.class),
                mock(StockTransactionRepository.class), mock(AuditWriter.class));
    }

    private Ingredient ingredient(Integer shelfLifeDays) {
        return Ingredient.create("COF", "Coffee", UUID.randomUUID(), new BigDecimal("1.0000"), true, true,
                shelfLifeDays, true);
    }

    @Test
    void opensFreshLotAndDerivesDefaultExpiryFromShelfLife() {
        InventoryBatchRepository repo = mock(InventoryBatchRepository.class);
        when(repo.findByLotForUpdate(eq(STORE), any(), eq("LOT-9"))).thenReturn(Optional.empty());
        when(repo.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        Ingredient ing = ingredient(30);
        UUID batchId = service(repo).onReceipt(STORE, ing, "LOT-9", null, new BigDecimal("20"), new BigDecimal("0.05"));

        ArgumentCaptor<InventoryBatch> captor = ArgumentCaptor.forClass(InventoryBatch.class);
        verify(repo).saveAndFlush(captor.capture());
        verify(repo, never()).increment(any(), any(), any());
        InventoryBatch saved = captor.getValue();
        assertEquals("LOT-9", saved.lotNumber());
        assertEquals(0, new BigDecimal("20").compareTo(saved.quantityBase()));
        assertEquals(LocalDate.now().plusDays(30), saved.expiryDate(), "shelf-life default expiry applied");
        assertEquals(saved.id(), batchId);
    }

    @Test
    void mergesIntoAnExistingLotOfTheSameNumber() {
        InventoryBatchRepository repo = mock(InventoryBatchRepository.class);
        InventoryBatch existing = InventoryBatch.open(STORE, UUID.randomUUID(), "LOT-9", null, new BigDecimal("5"),
                new BigDecimal("0.05"));
        when(repo.findByLotForUpdate(eq(STORE), eq(existing.ingredientId()), eq("LOT-9"))).thenReturn(Optional.of(existing));

        UUID id = service(repo).onReceipt(STORE, ingredientWithId(existing.ingredientId()), "LOT-9", null,
                new BigDecimal("10"), new BigDecimal("0.06"));

        assertEquals(existing.id(), id);
        verify(repo).increment(existing.id(), new BigDecimal("10"), new BigDecimal("0.06"));
        verify(repo, never()).saveAndFlush(any());
    }

    @Test
    void unlottedReceiptFallsBackToTheUnlottedSentinel() {
        InventoryBatchRepository repo = mock(InventoryBatchRepository.class);
        when(repo.findByLotForUpdate(eq(STORE), any(), eq(InventoryBatch.UNLOTTED))).thenReturn(Optional.empty());
        when(repo.saveAndFlush(any())).thenAnswer(i -> i.getArgument(0));

        service(repo).onReceipt(STORE, ingredient(null), "   ", null, new BigDecimal("4"), new BigDecimal("0.02"));

        ArgumentCaptor<InventoryBatch> captor = ArgumentCaptor.forClass(InventoryBatch.class);
        verify(repo).saveAndFlush(captor.capture());
        assertEquals(InventoryBatch.UNLOTTED, captor.getValue().lotNumber(), "blank lot -> UNLOTTED");
        assertNull(captor.getValue().expiryDate(), "no lot expiry and no shelf life -> null");
    }

    private Ingredient ingredientWithId(UUID id) {
        // Ingredient ids are application-generated; exercise the merge lookup by
        // building one and overriding its id reflectively (id is @Id, private).
        Ingredient ing = ingredient(null);
        try {
            var f = Ingredient.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(ing, id);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
        return ing;
    }
}
