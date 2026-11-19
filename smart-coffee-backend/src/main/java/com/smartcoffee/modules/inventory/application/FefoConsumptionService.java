package com.smartcoffee.modules.inventory.application;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.modules.inventory.domain.InventoryBatch;
import com.smartcoffee.modules.inventory.domain.StockTransactionLine;
import com.smartcoffee.modules.inventory.persistence.InventoryBatchRepository;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * FEFO (first-expiry-first-out) batch draw (V038, M3). Given a quantity to
 * consume for a tracked ingredient at a store, it locks the ACTIVE, unexpired
 * lots in expiry order and draws them down through the guarded native
 * statements on {@link InventoryBatchRepository} — never a setter — returning
 * one {@link StockTransactionLine} per lot touched so the caller can stamp the
 * batch id onto the same ledger transaction that carries the aggregate movement.
 *
 * <p>This service does NOT touch the aggregate balance: availability stays the
 * aggregate's authority and the aggregate mutation happens in the caller's
 * guarded statement. The batch draw only mirrors that movement onto the lots.
 * It is always invoked inside the caller's {@code @Transactional} command, so if
 * the active unexpired supply cannot cover the quantity (a data drift, since the
 * lot sum is kept synchronized with the aggregate) the whole command rolls back
 * rather than leaving a half-applied draw.
 */
@Service
public class FefoConsumptionService {

    private final InventoryBatchRepository batches;

    public FefoConsumptionService(InventoryBatchRepository batches) {
        this.batches = batches;
    }

    /**
     * Draws {@code quantity} across the store's FEFO lots of an ingredient and
     * returns the per-lot ledger lines. The aggregate has already been reduced
     * to {@code balanceBefore - quantity}, so the lines thread that drop through
     * each lot (running balance from {@code balanceBefore} down to the end).
     *
     * @throws ConflictException {@code INSUFFICIENT_STOCK} if the unexpired lot
     *         supply falls short of {@code quantity} (rolls the caller back).
     */
    @Transactional
    public List<StockTransactionLine> drawLines(UUID storeId, UUID ingredientId, BigDecimal quantity,
            BigDecimal balanceBefore) {
        List<StockTransactionLine> lines = new ArrayList<>();
        if (quantity == null || quantity.signum() <= 0) {
            return lines;
        }
        BigDecimal remaining = quantity;
        BigDecimal running = balanceBefore;
        for (InventoryBatch batch : batches.pickFefoForUpdate(storeId, ingredientId)) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal take = batch.quantityBase().min(remaining);
            if (take.signum() <= 0) {
                continue;
            }
            if (batches.decrementIfEnough(batch.id(), take) != 1) {
                throw new ConflictException("INSUFFICIENT_STOCK", "A concurrent draw took this lot first");
            }
            BigDecimal before = running;
            running = running.subtract(take);
            remaining = remaining.subtract(take);
            lines.add(new StockTransactionLine(ingredientId, take.negate(), batch.unitCost(), before, running,
                    batch.id()));
        }
        if (remaining.signum() > 0) {
            throw new ConflictException("INSUFFICIENT_STOCK",
                    "Expiry-tracked supply is short of the quantity being consumed");
        }
        return lines;
    }
}
