package com.smartcoffee.modules.production.application;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.modules.inventory.domain.Ingredient;
import com.smartcoffee.modules.inventory.domain.InventoryBalance;
import com.smartcoffee.modules.inventory.domain.StockTransaction;
import com.smartcoffee.modules.inventory.domain.StockTransactionLine;
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
import com.smartcoffee.modules.production.domain.ProductionOrderWaste;
import com.smartcoffee.modules.production.domain.ProductionOrderWasteReason;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Atomic inventory posting for the {@code ProductionOrder} completion command
 * (Slice 5A.2 §5). Performs balance mutations, ledger writes, and batch
 * creation inside the caller's {@code @Transactional} boundary — the caller
 * commits or rolls back as one unit.
 *
 * <p>Posting policy (spec §2-§4):
 * <ul>
 *   <li>Material consumption = plannedPerUnit × grossProducedOutput, where
 *       grossProducedOutput = actualGoodOutput + finishedOutputWaste. This
 *       avoids understating raw-material usage when part of the batch is wasted.</li>
 *   <li>Finished-good output posting = actualGoodOutput (positive delta only).
 *       Waste never enters the balance.</li>
 *   <li>Waste rows are recorded on the aggregate for audit; a separate DAMAGED
 *       ledger is NOT written (the stock never physically existed at the
 *       finished-good side).</li>
 *   <li>When the output ingredient has {@code trackBatches=true}, an
 *       {@code inventory.inventory_batches} row is created atomically.</li>
 *   <li>{@code reservedStock} is NEVER touched by production completion.</li>
 * </ul>
 *
 * <p>Deterministic ordering: all affected ingredient IDs are sorted ascending
 * before balance updates to prevent deadlocks with concurrent transactions
 * (same convention as reservation finalization).
 */
@Service
public class ProductionInventoryService {

    private final InventoryBalanceRepository balances;
    private final StockTransactionRepository ledger;
    private final InventoryBatchRepository batchRepo;
    private final IngredientRepository ingredients;
    private final BatchService batchService;
    private final FefoConsumptionService fefo;

    public ProductionInventoryService(InventoryBalanceRepository balances,
            StockTransactionRepository ledger,
            InventoryBatchRepository batchRepo,
            IngredientRepository ingredients,
            BatchService batchService,
            FefoConsumptionService fefo) {
        this.balances = balances;
        this.ledger = ledger;
        this.batchRepo = batchRepo;
        this.ingredients = ingredients;
        this.batchService = batchService;
        this.fefo = fefo;
    }

    /**
     * Result of a successful completion posting: actual consumed quantities
     * per material and the optional output batch id.
     */
    public record CompletionResult(
            Map<UUID, BigDecimal> consumedPerItem,
            UUID outputBatchId) {}

    /**
     * Executes the full completion posting inside the caller's transaction.
     *
     * @param order                the locked IN_PROGRESS aggregate with frozen snapshots
     * @param actualGoodOutput     net saleable finished units (≥ 0)
     * @param wasteEntries         caller-supplied waste (may be null/empty)
     * @param manufacturedDate     for batch creation when trackBatches
     * @param expiryDate           for batch expiry when trackBatches
     * @param lotNumber            caller-supplied or null (server generates UNLOTTED)
     * @param actorId              performing user
     * @param idempotencyKey       for the ledger traceability
     * @return consumed map and optional batch id
     */
    @Transactional
    public CompletionResult postCompletion(ProductionOrder order,
            BigDecimal actualGoodOutput,
            List<WasteEntryDto> wasteEntries,
            LocalDate manufacturedDate,
            LocalDate expiryDate,
            String lotNumber,
            UUID actorId,
            String idempotencyKey) {

        // The guarded balance writes below are native queries flagged
        // clearAutomatically=true; they detach this aggregate mid-transaction.
        // materials/outputs are already iterated later in this method while the
        // session is still bound, but an order with no waste never touches its
        // lazy waste collection until the response mapper runs after the
        // detach — which would throw LazyInitializationException. Force it to
        // initialize here, while the session is open, to keep the mapping safe.
        order.waste();

        // --- 1. Compute finished-output waste total ---
        BigDecimal finishedOutputWaste = BigDecimal.ZERO;
        ProductionOrderOutput outputRow = order.outputs().get(0);
        UUID outputItemId = outputRow.outputInventoryItemId();

        if (wasteEntries != null) {
            for (WasteEntryDto w : wasteEntries) {
                if (w.inventoryItemId().equals(outputItemId)) {
                    finishedOutputWaste = finishedOutputWaste.add(w.quantity());
                }
            }
        }

        // --- 2. grossProducedOutput = actualGoodOutput + finishedOutputWaste ---
        BigDecimal grossProduced = actualGoodOutput.add(finishedOutputWaste);

        // --- 3. Calculate actual consumed per material ---
        // plannedPerUnit for each material = quantityPlannedBase / plannedOutputQuantity
        BigDecimal plannedOutput = order.plannedOutputQuantity();
        Map<UUID, BigDecimal> actualConsumed = new LinkedHashMap<>();
        for (ProductionOrderMaterial m : order.materials()) {
            BigDecimal perUnit = m.quantityPlannedBase().divide(plannedOutput, 10, RoundingMode.HALF_UP);
            BigDecimal consumed = perUnit.multiply(grossProduced).setScale(4, RoundingMode.CEILING);
            actualConsumed.merge(m.inventoryItemId(), consumed, (left, right) -> left.add(right));
        }

        // --- 4. Validate waste items belong to material set or output ---
        Set<UUID> validWasteItems = new HashSet<>();
        order.materials().forEach(m -> validWasteItems.add(m.inventoryItemId()));
        validWasteItems.add(outputItemId);

        if (wasteEntries != null) {
            for (WasteEntryDto w : wasteEntries) {
                if (!validWasteItems.contains(w.inventoryItemId())) {
                    throw new ValidationException("PRODUCTION_WASTE_ITEM_INVALID",
                            "Waste references an item not on this order's materials or output");
                }
                if (w.quantity() == null || w.quantity().signum() <= 0) {
                    throw new ValidationException("PRODUCTION_WASTE_QUANTITY_INVALID",
                            "Waste quantity must be positive");
                }
            }
        }

        // --- 5. Sort ingredient IDs for deterministic lock ordering ---
        List<UUID> sortedMaterialIds = new ArrayList<>(actualConsumed.keySet());
        Collections.sort(sortedMaterialIds);

        // --- 6. Guard: production ledger uniqueness ---
        if (ledger.existsByReferenceIdAndType(order.id(), StockTransactionType.PRODUCTION_CONSUMPTION)) {
            throw new ConflictException("PRODUCTION_ALREADY_COMPLETED",
                    "A consumption ledger row already exists for this production order");
        }

        // --- 7. Consume materials (decrement current_stock, never touch reserved) ---
        List<StockTransactionLine> consumptionLines = new ArrayList<>();
        for (UUID ingredientId : sortedMaterialIds) {
            BigDecimal qty = actualConsumed.get(ingredientId);
            if (qty.signum() <= 0) continue;

            balances.ensureRow(order.storeId(), ingredientId);
            InventoryBalance before = balances.find(order.storeId(), ingredientId)
                    .orElseThrow(() -> new ResourceNotFoundException("PRODUCTION_MATERIAL_NOT_FOUND",
                            "No inventory balance for material " + ingredientId));

            int updated = balances.adjustDown(order.storeId(), ingredientId, qty);
            if (updated == 0) {
                throw new ConflictException("PRODUCTION_MATERIAL_INSUFFICIENT",
                        "Insufficient uncommitted stock for material " + ingredientId);
            }

            BigDecimal after = balances.find(order.storeId(), ingredientId)
                    .orElseThrow().currentStock();

            // FEFO batch draw for tracked ingredients
            Ingredient ing = ingredients.findById(ingredientId).orElse(null);
            if (ing != null && ing.trackBatches()) {
                List<StockTransactionLine> batchLines = fefo.drawLines(
                        order.storeId(), ingredientId, qty, before.currentStock());
                consumptionLines.addAll(batchLines);
            } else {
                consumptionLines.add(new StockTransactionLine(
                        ingredientId, qty.negate(), null, before.currentStock(), after));
            }
        }

        // --- 8. Write PRODUCTION_CONSUMPTION ledger (one per order) ---
        if (!consumptionLines.isEmpty()) {
            ledger.save(StockTransaction.create(order.storeId(),
                    StockTransactionType.PRODUCTION_CONSUMPTION,
                    "PRODUCTION_ORDER", order.id(), actorId,
                    consumptionLines, "Production completion " + order.number(),
                    null, null, null, idempotencyKey));
        }

        // --- 9. Post finished-good output ---
        if (actualGoodOutput.signum() > 0) {
            balances.ensureRow(order.storeId(), outputItemId);
            BigDecimal outBefore = balances.find(order.storeId(), outputItemId)
                    .orElseThrow().currentStock();
            balances.adjustUp(order.storeId(), outputItemId, actualGoodOutput);
            BigDecimal outAfter = balances.find(order.storeId(), outputItemId)
                    .orElseThrow().currentStock();

            // Batch creation when trackBatches on the output ingredient
            UUID batchId = null;
            Ingredient outputIng = ingredients.findById(outputItemId).orElse(null);
            if (outputIng != null && outputIng.trackBatches()) {
                if (lotNumber != null && lotNumber.isBlank()) {
                    throw new ValidationException("PRODUCTION_BATCH_REQUIRED",
                            "Lot number must be non-blank for batch-tracked output");
                }
                // Check duplicate lot
                String effectiveLot = lotNumber == null
                        ? com.smartcoffee.modules.inventory.domain.InventoryBatch.UNLOTTED
                        : lotNumber.trim();
                Optional<com.smartcoffee.modules.inventory.domain.InventoryBatch> existing =
                        batchRepo.findByLotForUpdate(order.storeId(), outputItemId, effectiveLot);
                if (existing.isPresent()) {
                    throw new ConflictException("PRODUCTION_BATCH_DUPLICATE",
                            "A batch with this lot number already exists for this item at this store");
                }
                LocalDate resolvedExpiry = expiryDate;
                if (resolvedExpiry == null && outputIng.shelfLifeDays() != null) {
                    resolvedExpiry = (manufacturedDate != null ? manufacturedDate : LocalDate.now())
                            .plusDays(outputIng.shelfLifeDays());
                }
                batchId = batchService.recordStockIn(order.storeId(), outputItemId,
                        lotNumber, resolvedExpiry, actualGoodOutput, BigDecimal.ZERO);
            }

            ledger.save(StockTransaction.create(order.storeId(),
                    StockTransactionType.PRODUCTION_OUTPUT,
                    "PRODUCTION_ORDER", order.id(), actorId,
                    List.of(new StockTransactionLine(outputItemId, actualGoodOutput, null,
                            outBefore, outAfter, batchId)),
                    "Production output " + order.number(),
                    null, null, null, idempotencyKey));

            // Record actual on the output row
            outputRow.recordActual(actualGoodOutput, batchId,
                    expiryDate != null ? expiryDate : null, "GOOD");
        } else {
            // zero output still records actual on the row
            outputRow.recordActual(BigDecimal.ZERO, null, null, "GOOD");
        }

        // --- 10. Record waste rows on the aggregate ---
        if (wasteEntries != null) {
            for (WasteEntryDto w : wasteEntries) {
                ProductionOrderWasteReason reason;
                try {
                    reason = ProductionOrderWasteReason.valueOf(w.reasonCode());
                } catch (IllegalArgumentException e) {
                    throw new ValidationException("PRODUCTION_WASTE_ITEM_INVALID",
                            "Unknown waste reason: " + w.reasonCode());
                }
                if (reason == ProductionOrderWasteReason.OTHER
                        && (w.notes() == null || w.notes().isBlank())) {
                    throw new ValidationException("PRODUCTION_WASTE_ITEM_INVALID",
                            "OTHER waste reason requires notes");
                }
                ProductionOrderWaste waste = ProductionOrderWaste.create(
                        w.inventoryItemId(), w.quantity(), w.unitCode(), reason, w.notes(), actorId);
                order.addWaste(waste);
            }
        }

        // --- 11. Record consumed quantities on material rows ---
        for (ProductionOrderMaterial m : order.materials()) {
            BigDecimal consumed = actualConsumed.getOrDefault(m.inventoryItemId(), BigDecimal.ZERO);
            m.recordConsumed(consumed);
        }

        return new CompletionResult(actualConsumed, null);
    }

    /**
     * Checks whether any production inventory transaction exists for this order
     * (used by cancel guard — spec §9).
     */
    public boolean hasProductionLedger(UUID productionOrderId) {
        return ledger.existsByReferenceIdAndType(productionOrderId, StockTransactionType.PRODUCTION_CONSUMPTION)
                || ledger.existsByReferenceIdAndType(productionOrderId, StockTransactionType.PRODUCTION_OUTPUT);
    }
}
