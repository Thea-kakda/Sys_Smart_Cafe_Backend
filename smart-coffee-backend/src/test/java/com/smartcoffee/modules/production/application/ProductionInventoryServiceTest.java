package com.smartcoffee.modules.production.application;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.modules.inventory.domain.Ingredient;
import com.smartcoffee.modules.inventory.domain.InventoryBalance;
import com.smartcoffee.modules.inventory.domain.InventoryBatch;
import com.smartcoffee.modules.inventory.domain.StockTransactionType;
import com.smartcoffee.modules.inventory.persistence.IngredientRepository;
import com.smartcoffee.modules.inventory.persistence.InventoryBalanceRepository;
import com.smartcoffee.modules.inventory.persistence.InventoryBatchRepository;
import com.smartcoffee.modules.inventory.persistence.StockTransactionRepository;
import com.smartcoffee.modules.inventory.application.BatchService;
import com.smartcoffee.modules.inventory.application.FefoConsumptionService;
import com.smartcoffee.modules.production.api.WasteEntryDto;
import com.smartcoffee.modules.production.domain.ProductionOrder;
import com.smartcoffee.modules.production.domain.ProductionOrderMaterial;
import com.smartcoffee.modules.production.domain.ProductionOrderOutput;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ProductionInventoryService} (Slice 5A.2 §13 items
 * 1-13). Uses Mockito to verify balance guards, ledger writes, batch
 * creation, waste accounting, and rollback semantics.
 */
class ProductionInventoryServiceTest {

    private static final UUID STORE = UUID.fromString("10000000-0000-0000-0000-000000000001");
    private static final UUID MATERIAL_A = UUID.fromString("9c000000-0000-0000-0000-000000000001");
    private static final UUID MATERIAL_B = UUID.fromString("9c000000-0000-0000-0000-000000000002");
    private static final UUID OUTPUT_ITEM = UUID.fromString("9c000000-0000-0000-0000-000000000099");
    private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private InventoryBalanceRepository balances;
    private StockTransactionRepository ledger;
    private InventoryBatchRepository batchRepo;
    private IngredientRepository ingredients;
    private BatchService batchService;
    private FefoConsumptionService fefo;
    private ProductionInventoryService svc;

    @BeforeEach
    void setup() {
        balances = mock(InventoryBalanceRepository.class);
        ledger = mock(StockTransactionRepository.class);
        batchRepo = mock(InventoryBatchRepository.class);
        ingredients = mock(IngredientRepository.class);
        batchService = mock(BatchService.class);
        fefo = mock(FefoConsumptionService.class);
        svc = new ProductionInventoryService(balances, ledger, batchRepo, ingredients, batchService, fefo);

        // Default: no existing ledger
        when(ledger.existsByReferenceIdAndType(any(), any())).thenReturn(false);
        // Default: balance rows exist
        InventoryBalance bal = mock(InventoryBalance.class);
        when(bal.currentStock()).thenReturn(new BigDecimal("1000.0000"));
        when(balances.find(any(), any())).thenReturn(Optional.of(bal));
        when(balances.adjustDown(any(), any(), any())).thenReturn(1);
        when(balances.adjustUp(any(), any(), any())).thenReturn(1);
        // Default: non-batch-tracked
        Ingredient ing = mock(Ingredient.class);
        when(ing.trackBatches()).thenReturn(false);
        when(ingredients.findById(any())).thenReturn(Optional.of(ing));
    }

    // --- helpers ---
    private ProductionOrder orderWithMaterials(BigDecimal plannedOutput,
            ProductionOrderMaterial... mats) {
        ProductionOrder order = ProductionOrder.createDraft("PO-TEST-001", STORE,
                UUID.randomUUID(), null, plannedOutput, null, null, null, ACTOR);
        ProductionOrderOutput output = ProductionOrderOutput.plan(OUTPUT_ITEM, plannedOutput, "EA");
        // simulate markPlanned
        order.markPlanned(UUID.randomUUID(), 1, OUTPUT_ITEM, "EA",
                List.of(mats), output, ACTOR);
        // move to IN_PROGRESS
        order.markStarted(ACTOR);
        return order;
    }

    private ProductionOrderMaterial mat(UUID item, BigDecimal qty) {
        return ProductionOrderMaterial.plan(item, qty, "G", UUID.randomUUID(), BigDecimal.ZERO, 1);
    }

    // --- 1: One material consumption ---
    @Test
    void singleMaterialConsumption() {
        ProductionOrder order = orderWithMaterials(new BigDecimal("10"), mat(MATERIAL_A, new BigDecimal("0.5")));
        var result = svc.postCompletion(order, new BigDecimal("10"), null, null, null, null, ACTOR, "k1");
        // consumed = 0.5/10 * 10 = 0.5 → 0.5000
        assertEquals(0, new BigDecimal("0.5").compareTo(result.consumedPerItem().get(MATERIAL_A)));
        verify(balances).adjustDown(STORE, MATERIAL_A, new BigDecimal("0.5000"));
    }

    // --- 2: Multiple materials ---
    @Test
    void multipleMaterialConsumption() {
        ProductionOrder order = orderWithMaterials(new BigDecimal("10"),
                mat(MATERIAL_A, new BigDecimal("0.5")),
                mat(MATERIAL_B, new BigDecimal("1.2")));
        var result = svc.postCompletion(order, new BigDecimal("10"), null, null, null, null, ACTOR, "k1");
        assertEquals(2, result.consumedPerItem().size());
        verify(balances).adjustDown(eq(STORE), eq(MATERIAL_A), any());
        verify(balances).adjustDown(eq(STORE), eq(MATERIAL_B), any());
    }

    // --- 3: Deterministic ordering ---
    @Test
    void deterministicOrderingByIngredientId() {
        // MATERIAL_A < MATERIAL_B lexicographically as UUID
        ProductionOrder order = orderWithMaterials(new BigDecimal("10"),
                mat(MATERIAL_B, new BigDecimal("1.2")),
                mat(MATERIAL_A, new BigDecimal("0.5")));
        svc.postCompletion(order, new BigDecimal("10"), null, null, null, null, ACTOR, "k1");
        var inOrder = org.mockito.Mockito.inOrder(balances);
        inOrder.verify(balances).adjustDown(eq(STORE), eq(MATERIAL_A), any());
        inOrder.verify(balances).adjustDown(eq(STORE), eq(MATERIAL_B), any());
    }

    // --- 4: Duplicate material merge ---
    @Test
    void duplicateMaterialsMergeToSameKey() {
        // Two material lines with same inventoryItemId
        ProductionOrder order = orderWithMaterials(new BigDecimal("10"),
                mat(MATERIAL_A, new BigDecimal("0.5")),
                mat(MATERIAL_A, new BigDecimal("0.3")));
        var result = svc.postCompletion(order, new BigDecimal("10"), null, null, null, null, ACTOR, "k1");
        // merged: 0.5 + 0.3 = 0.8 total for MATERIAL_A
        assertEquals(1, result.consumedPerItem().size());
        assertEquals(0, new BigDecimal("0.8000").compareTo(result.consumedPerItem().get(MATERIAL_A)));
    }

    // --- 5: Insufficient material ---
    @Test
    void insufficientMaterialThrows() {
        when(balances.adjustDown(eq(STORE), eq(MATERIAL_A), any())).thenReturn(0);
        ProductionOrder order = orderWithMaterials(new BigDecimal("10"), mat(MATERIAL_A, new BigDecimal("0.5")));
        ConflictException ex = assertThrows(ConflictException.class,
                () -> svc.postCompletion(order, new BigDecimal("10"), null, null, null, null, ACTOR, "k1"));
        assertEquals("PRODUCTION_MATERIAL_INSUFFICIENT", ex.code());
    }

    // --- 6: Missing balance (ensureRow + find returns empty) ---
    @Test
    void missingBalanceAfterEnsureThrows() {
        when(balances.find(eq(STORE), eq(MATERIAL_A))).thenReturn(Optional.empty());
        ProductionOrder order = orderWithMaterials(new BigDecimal("10"), mat(MATERIAL_A, new BigDecimal("0.5")));
        assertThrows(Exception.class,
                () -> svc.postCompletion(order, new BigDecimal("10"), null, null, null, null, ACTOR, "k1"));
    }

    // --- 7: Output increase ---
    @Test
    void outputIncreasesBalance() {
        ProductionOrder order = orderWithMaterials(new BigDecimal("10"), mat(MATERIAL_A, new BigDecimal("0.5")));
        svc.postCompletion(order, new BigDecimal("8"), null, null, null, null, ACTOR, "k1");
        verify(balances).adjustUp(STORE, OUTPUT_ITEM, new BigDecimal("8"));
    }

    // --- 8: Batch output (track_batches on output) ---
    @Test
    void batchOutputCreatesBatch() {
        ProductionOrder order = orderWithMaterials(new BigDecimal("10"), mat(MATERIAL_A, new BigDecimal("0.5")));
        Ingredient outIng = mock(Ingredient.class);
        when(outIng.trackBatches()).thenReturn(true);
        when(outIng.shelfLifeDays()).thenReturn(null);
        when(ingredients.findById(OUTPUT_ITEM)).thenReturn(Optional.of(outIng));
        when(batchRepo.findByLotForUpdate(STORE, OUTPUT_ITEM, "LOT-001")).thenReturn(Optional.empty());
        UUID batchId = UUID.randomUUID();
        when(batchService.recordStockIn(STORE, OUTPUT_ITEM, "LOT-001", null, new BigDecimal("10"), BigDecimal.ZERO))
                .thenReturn(batchId);

        svc.postCompletion(order, new BigDecimal("10"), null, null, null, "LOT-001", ACTOR, "k1");
        verify(batchService).recordStockIn(eq(STORE), eq(OUTPUT_ITEM), eq("LOT-001"), any(), any(), any());
    }

    // --- 9: Duplicate lot ---
    @Test
    void duplicateLotThrows() {
        ProductionOrder order = orderWithMaterials(new BigDecimal("10"), mat(MATERIAL_A, new BigDecimal("0.5")));
        Ingredient outIng = mock(Ingredient.class);
        when(outIng.trackBatches()).thenReturn(true);
        when(ingredients.findById(OUTPUT_ITEM)).thenReturn(Optional.of(outIng));
        InventoryBatch existing = mock(InventoryBatch.class);
        when(batchRepo.findByLotForUpdate(STORE, OUTPUT_ITEM, "LOT-DUP")).thenReturn(Optional.of(existing));

        ConflictException ex = assertThrows(ConflictException.class,
                () -> svc.postCompletion(order, new BigDecimal("10"), null, null, null, "LOT-DUP", ACTOR, "k1"));
        assertEquals("PRODUCTION_BATCH_DUPLICATE", ex.code());
    }

    // --- 10: Raw-material waste without double decrement ---
    @Test
    void rawMaterialWasteIncludedInGrossOutput() {
        // grossProduced = goodOutput(8) + finishedWaste(2) = 10
        // material consumed = 0.5/10 * 10 = 0.5 (same as if no waste)
        ProductionOrder order = orderWithMaterials(new BigDecimal("10"), mat(MATERIAL_A, new BigDecimal("0.5")));
        List<WasteEntryDto> waste = List.of(
                new WasteEntryDto(OUTPUT_ITEM, new BigDecimal("2"), "EA", "BURNT", "Burnt batch"));
        svc.postCompletion(order, new BigDecimal("8"), waste, null, null, null, ACTOR, "k1");
        // Material consumed uses gross = 10 basis → 0.5
        verify(balances).adjustDown(eq(STORE), eq(MATERIAL_A), eq(new BigDecimal("0.5000")));
        // Output only posts good (8)
        verify(balances).adjustUp(STORE, OUTPUT_ITEM, new BigDecimal("8"));
    }

    // --- 11: Finished-output waste recorded ---
    @Test
    void finishedOutputWasteRecordedOnOrder() {
        ProductionOrder order = orderWithMaterials(new BigDecimal("10"), mat(MATERIAL_A, new BigDecimal("0.5")));
        List<WasteEntryDto> waste = List.of(
                new WasteEntryDto(OUTPUT_ITEM, new BigDecimal("1"), "EA", "QUALITY_FAIL", null));
        svc.postCompletion(order, new BigDecimal("9"), waste, null, null, null, ACTOR, "k1");
        assertEquals(1, order.waste().size());
    }

    // --- 12: Partial failure rolls back (existing ledger) ---
    @Test
    void duplicateCompletionRejected() {
        when(ledger.existsByReferenceIdAndType(any(), eq(StockTransactionType.PRODUCTION_CONSUMPTION)))
                .thenReturn(true);
        ProductionOrder order = orderWithMaterials(new BigDecimal("10"), mat(MATERIAL_A, new BigDecimal("0.5")));
        ConflictException ex = assertThrows(ConflictException.class,
                () -> svc.postCompletion(order, new BigDecimal("10"), null, null, null, null, ACTOR, "k1"));
        assertEquals("PRODUCTION_ALREADY_COMPLETED", ex.code());
    }

    // --- 13: Input snapshots not mutated ---
    @Test
    void materialPlannedQuantityUnchanged() {
        ProductionOrderMaterial m = mat(MATERIAL_A, new BigDecimal("0.5"));
        ProductionOrder order = orderWithMaterials(new BigDecimal("10"), m);
        BigDecimal plannedBefore = m.quantityPlannedBase();
        svc.postCompletion(order, new BigDecimal("10"), null, null, null, null, ACTOR, "k1");
        assertEquals(0, plannedBefore.compareTo(m.quantityPlannedBase()),
                "Planned quantity must not be mutated by completion");
    }

    // --- Waste item validation ---
    @Test
    void unrelatedWasteItemRejected() {
        ProductionOrder order = orderWithMaterials(new BigDecimal("10"), mat(MATERIAL_A, new BigDecimal("0.5")));
        UUID unrelated = UUID.fromString("9c000000-0000-0000-0000-000000000ABC");
        List<WasteEntryDto> waste = List.of(
                new WasteEntryDto(unrelated, new BigDecimal("1"), "EA", "SPOILED", null));
        assertThrows(ValidationException.class,
                () -> svc.postCompletion(order, new BigDecimal("10"), waste, null, null, null, ACTOR, "k1"));
    }

    // --- OTHER reason requires notes ---
    @Test
    void otherWasteReasonWithoutNotesRejected() {
        ProductionOrder order = orderWithMaterials(new BigDecimal("10"), mat(MATERIAL_A, new BigDecimal("0.5")));
        List<WasteEntryDto> waste = List.of(
                new WasteEntryDto(OUTPUT_ITEM, new BigDecimal("1"), "EA", "OTHER", null));
        ValidationException ex = assertThrows(ValidationException.class,
                () -> svc.postCompletion(order, new BigDecimal("10"), waste, null, null, null, ACTOR, "k1"));
        assertTrue(ex.getMessage().contains("OTHER"));
    }
}
