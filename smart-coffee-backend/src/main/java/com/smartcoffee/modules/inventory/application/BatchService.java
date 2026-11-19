package com.smartcoffee.modules.inventory.application;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.modules.audit.application.AuditWriter;
import com.smartcoffee.modules.inventory.api.BatchExpiryResponse;
import com.smartcoffee.modules.inventory.domain.Ingredient;
import com.smartcoffee.modules.inventory.domain.InventoryBatch;
import com.smartcoffee.modules.inventory.domain.InventoryBatchStatus;
import com.smartcoffee.modules.inventory.domain.StockTransaction;
import com.smartcoffee.modules.inventory.domain.StockTransactionLine;
import com.smartcoffee.modules.inventory.domain.StockTransactionType;
import com.smartcoffee.modules.inventory.persistence.BatchQueryRepository;
import com.smartcoffee.modules.inventory.persistence.BatchQueryRepository.BatchRow;
import com.smartcoffee.modules.inventory.persistence.InventoryBalanceRepository;
import com.smartcoffee.modules.inventory.persistence.InventoryBatchRepository;
import com.smartcoffee.modules.inventory.persistence.StockTransactionRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Batch/lot lifecycle (V038, M3): opening/incrementing lots on receipt and
 * writing them off on expiry/waste. Every quantity change goes through the
 * guarded native statements on {@link InventoryBatchRepository} (never a
 * setter), and the aggregate balance stays the availability authority — the
 * sum of a tracked ingredient's ACTIVE lots is kept synchronized with it.
 *
 * <p>Receiving (a valued stock-in) is the {@link #onReceipt} hook the goods-
 * receipt command calls per lot for {@code track_batches} ingredients: it
 * merges into the same lot number (defaulting un-lotted receipts to
 * {@link InventoryBatch#UNLOTTED}) or opens a fresh lot, deriving a default
 * expiry from {@code shelf_life_days} when the receipt line carries none. A
 * {@link #writeOff} retires one lot: it draws the lot's remaining quantity out
 * of the aggregate through the guarded {@code adjustDown} (so it can never breach
 * reservations or go negative — a lost race rolls back with 409), stamps the
 * lot EXPIRED/ADJUSTED_OUT, and writes exactly one DAMAGED ledger line carrying
 * the batch id, audited with the mandatory reason.
 */
@Service
public class BatchService {

    private final InventoryBatchRepository batches;
    private final BatchQueryRepository batchReads;
    private final InventoryBalanceRepository balances;
    private final StockTransactionRepository ledger;
    private final AuditWriter audit;

    public BatchService(InventoryBatchRepository batches, BatchQueryRepository batchReads,
            InventoryBalanceRepository balances, StockTransactionRepository ledger, AuditWriter audit) {
        this.batches = batches;
        this.batchReads = batchReads;
        this.balances = balances;
        this.ledger = ledger;
        this.audit = audit;
    }

    /**
     * Records a received quantity against a lot for a tracked ingredient (the
     * M1 receiving hook). Merges into an existing lot of the same number or
     * opens a new one; the caller has already moved the aggregate balance, so
     * this only mirrors the stock-in onto the lot. Returns the batch id.
     */
    @Transactional
    public UUID onReceipt(UUID storeId, Ingredient ingredient, String lotNumber, LocalDate expiryDate,
            BigDecimal quantity, BigDecimal unitCost) {
        return recordStockIn(storeId, ingredient.id(), lotNumber, resolveExpiry(ingredient, expiryDate), quantity,
                unitCost);
    }

    /**
     * Mirrors a valued stock-in onto a lot with an explicit (nullable) expiry —
     * no shelf-life defaulting. Used by goods receiving (via {@link #onReceipt})
     * and by a transfer receive at the destination, where the shipment carries no
     * lot/expiry so a fresh {@link InventoryBatch#UNLOTTED} lot is grown.
     */
    @Transactional
    public UUID recordStockIn(UUID storeId, UUID ingredientId, String lotNumber, LocalDate expiry,
            BigDecimal quantity, BigDecimal unitCost) {
        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("A batch stock-in must carry a positive quantity");
        }
        String lot = InventoryBatch.effectiveLot(lotNumber);
        BigDecimal cost = unitCost == null ? BigDecimal.ZERO : unitCost;
        return batches.findByLotForUpdate(storeId, ingredientId, lot)
                .map(existing -> {
                    batches.increment(existing.id(), quantity, cost);
                    return existing.id();
                })
                .orElseGet(() -> batches.saveAndFlush(
                        InventoryBatch.open(storeId, ingredientId, lot, expiry, quantity, cost)).id());
    }

    /**
     * Writes off (expires / wastes) one ACTIVE lot at a store. Retires the lot
     * and drives the equivalent quantity out of the aggregate balance through
     * the guarded {@code adjustDown}, stamping one DAMAGED ledger line with the
     * batch id. Permission + store scope are enforced by the controller.
     */
    @Transactional
    public BatchExpiryResponse writeOff(UUID actorUserId, UUID storeId, UUID batchId, String reason) {
        InventoryBatch batch = batches.findByIdForUpdate(batchId)
                .orElseThrow(() -> new ResourceNotFoundException("BATCH_NOT_FOUND", "Batch not found"));
        if (!batch.storeId().equals(storeId)) {
            throw new ConflictException("STORE_MISMATCH", "Batch belongs to a different store");
        }
        if (batch.status() != InventoryBatchStatus.ACTIVE) {
            throw new ConflictException("BATCH_NOT_ACTIVE", "Only an active batch can be written off");
        }
        BigDecimal quantity = batch.quantityBase();
        if (quantity.signum() <= 0) {
            throw new ConflictException("BATCH_EMPTY", "The batch holds no stock to write off");
        }

        balances.ensureRow(storeId, batch.ingredientId());
        BigDecimal before = balances.find(storeId, batch.ingredientId()).orElseThrow().currentStock();
        int updated = balances.adjustDown(storeId, batch.ingredientId(), quantity);
        if (updated != 1) {
            throw new ConflictException("INSUFFICIENT_STOCK",
                    "Writing off this batch would breach reserved or available stock");
        }
        boolean pastExpiry = batch.expiryDate() != null && !batch.expiryDate().isAfter(LocalDate.now());
        batches.retire(batchId, pastExpiry ? InventoryBatchStatus.EXPIRED.name() : InventoryBatchStatus.ADJUSTED_OUT.name());
        BigDecimal after = balances.find(storeId, batch.ingredientId()).orElseThrow().currentStock();

        String trimmed = reason == null ? null : reason.trim();
        StockTransaction tx = ledger.save(StockTransaction.create(storeId, StockTransactionType.DAMAGED,
                "INVENTORY_BATCH", batchId, actorUserId,
                List.of(new StockTransactionLine(batch.ingredientId(), quantity.negate(), batch.unitCost(), before,
                        after, batchId)),
                trimmed));

        audit.success(actorUserId, storeId, pastExpiry ? "BATCH_EXPIRED" : "BATCH_WRITTEN_OFF", "INVENTORY",
                "INVENTORY_BATCH", batchId, Map.of("lot", batch.lotNumber(), "quantity", quantity),
                Map.of("status", pastExpiry ? "EXPIRED" : "ADJUSTED_OUT", "transaction", tx.number()),
                (trimmed == null ? "Wrote off batch " + batch.lotNumber() : trimmed));

        return readBack(storeId, batch.ingredientId(), batchId);
    }

    // ------------------------------------------------------------- helpers

    private LocalDate resolveExpiry(Ingredient ingredient, LocalDate explicit) {
        if (explicit != null) {
            return explicit;
        }
        Integer shelfLife = ingredient.shelfLifeDays();
        return shelfLife == null ? null : LocalDate.now().plusDays(shelfLife);
    }

    private BatchExpiryResponse readBack(UUID storeId, UUID ingredientId, UUID batchId) {
        List<BatchRow> rows = batchReads.batches(storeId, true, ingredientId, false, 0);
        return rows.stream().filter(r -> r.getBatchId().equals(batchId)).findFirst()
                .map(BatchMapper::toResponse)
                .orElseThrow(() -> new ResourceNotFoundException("BATCH_NOT_FOUND", "Batch not found"));
    }
}
