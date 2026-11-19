package com.smartcoffee.modules.inventory.application;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.modules.audit.application.AuditWriter;
import com.smartcoffee.modules.inventory.api.GoodsReceiptLineResponse;
import com.smartcoffee.modules.inventory.api.GoodsReceiptResponse;
import com.smartcoffee.modules.inventory.api.ReceiveGoodsRequest;
import com.smartcoffee.modules.inventory.domain.*;
import com.smartcoffee.modules.inventory.persistence.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Goods receipt against a purchase order — the valued stock-in that a PO
 * represents. Guarded by {@code inventory:receive} plus the store scope.
 *
 * <p>The PO header is locked {@code PESSIMISTIC_WRITE} for the whole command, so
 * the over-receipt guard and the balance write cannot interleave with a
 * concurrent receipt. Balances change ONLY through the guarded native
 * statements on {@link InventoryBalanceRepository} (never a setter), exactly as
 * the manual-adjustment path does. Every received line contributes one signed
 * ledger line (with real balance_before/after) to a single
 * {@code PURCHASE_RECEIPT} {@link StockTransaction} referencing the receipt, and
 * the whole command is audited.
 *
 * <p>Ordering: the PO line totals (and header status transition) are computed
 * first — an over-receipt throws before any physical stock moves, and the
 * transaction rolls back so the ledger and the PO can never disagree. Then the
 * balances are incremented and the receipt + ledger + audit are written.
 */
@SuppressWarnings("null")
@Service
public class GoodsReceiptService {

    private final PurchaseOrderRepository orders;
    private final GoodsReceiptRepository receipts;
    private final IngredientRepository ingredients;
    private final InventoryBalanceRepository balances;
    private final StockTransactionRepository ledger;
    private final AuditWriter audit;
    private final PermissionEvaluator permissions;
    private final BatchService batches;

    public GoodsReceiptService(PurchaseOrderRepository orders, GoodsReceiptRepository receipts,
            IngredientRepository ingredients, InventoryBalanceRepository balances, StockTransactionRepository ledger,
            AuditWriter audit, PermissionEvaluator permissions, BatchService batches) {
        this.orders = orders;
        this.receipts = receipts;
        this.ingredients = ingredients;
        this.balances = balances;
        this.ledger = ledger;
        this.audit = audit;
        this.permissions = permissions;
        this.batches = batches;
    }

    @Transactional
    public GoodsReceiptResponse receive(CurrentActor actor, UUID storeId, UUID poId, ReceiveGoodsRequest req) {
        permissions.require(actor, PermissionCodes.INVENTORY_RECEIVE);

        PurchaseOrder po = orders.findByIdForUpdate(poId)
                .orElseThrow(() -> new ResourceNotFoundException("PURCHASE_ORDER_NOT_FOUND", "Purchase order not found"));
        if (!po.storeId().equals(storeId)) {
            throw new ConflictException("STORE_MISMATCH", "Purchase order belongs to a different store");
        }
        if (!po.isReceivable()) {
            throw new ConflictException("PO_NOT_RECEIVABLE", "Purchase order is not in a receivable status");
        }
        List<ReceiveGoodsRequest.Line> reqLines = req.lines();
        if (reqLines.stream().map(ReceiveGoodsRequest.Line::ingredientId).distinct().count() != reqLines.size()) {
            throw new ValidationException("DUPLICATE_LINE", "An ingredient appears on more than one receipt line");
        }

        // Resolve ingredients (existence + active) once; also lets the response carry labels.
        Map<UUID, Ingredient> ingMap = loadIngredients(reqLines);

        // Phase A — accumulate PO line totals; over-receipt aborts before any stock moves.
        List<ReceiptPlan> plan = new ArrayList<>();
        for (var line : reqLines) {
            Ingredient ingredient = ingMap.get(line.ingredientId());
            if (ingredient == null) {
                throw new ResourceNotFoundException("INGREDIENT_NOT_FOUND", "Ingredient not found");
            }
            if (!ingredient.active()) {
                throw new ValidationException("Ingredient is inactive: " + ingredient.code());
            }
            PurchaseOrderLine poLine = po.lineFor(line.ingredientId());
            if (poLine == null) {
                throw new ValidationException("LINE_NOT_ON_ORDER",
                        "Ingredient is not on the purchase order: " + ingredient.code());
            }
            try {
                poLine.markReceived(line.quantity());
            } catch (IllegalStateException over) {
                throw new ConflictException("OVER_RECEIPT",
                        "Receipt would exceed the ordered quantity for " + ingredient.code());
            }
            BigDecimal cost = line.unitCost() != null ? line.unitCost() : poLine.unitCost();
            plan.add(new ReceiptPlan(ingredient.id(), line.quantity(), cost, line.lotNumber(), line.expiryDate()));
        }
        po.applyReceiptTransition();

        // Phase B — physical stock-in through the guarded native mutations + ledger lines.
        List<StockTransactionLine> ledgerLines = new ArrayList<>();
        for (ReceiptPlan p : plan) {
            balances.ensureRow(storeId, p.ingredientId());
            BigDecimal before = balances.find(storeId, p.ingredientId()).orElseThrow().currentStock();
            int updated = balances.adjustUp(storeId, p.ingredientId(), p.quantity());
            if (updated != 1) {
                throw new ConflictException("INSUFFICIENT_STOCK", "Failed to open/update the balance row for receipt");
            }
            balances.restateAverageCost(storeId, p.ingredientId(), p.unitCost());
            BigDecimal after = balances.find(storeId, p.ingredientId()).orElseThrow().currentStock();
            ledgerLines.add(new StockTransactionLine(p.ingredientId(), p.quantity(), p.unitCost(), before, after));
            // M3: mirror the valued stock-in onto a lot for batch-tracked ingredients.
            Ingredient tracked = ingMap.get(p.ingredientId());
            if (tracked != null && tracked.trackBatches()) {
                batches.onReceipt(storeId, tracked, p.lotNumber(), p.expiryDate(), p.quantity(), p.unitCost());
            }
        }

        // Phase C — receipt header, one PURCHASE_RECEIPT ledger transaction, audit.
        GoodsReceipt receipt = GoodsReceipt.record(poId, storeId, actor.userId(),
                plan.stream().map(p -> GoodsReceiptLine.create(p.ingredientId(), p.quantity(), p.unitCost(),
                        p.lotNumber(), p.expiryDate())).toList());
        String reason = "Received purchase order " + po.number();
        StockTransaction tx = ledger.save(StockTransaction.create(storeId, StockTransactionType.PURCHASE_RECEIPT,
                "GOODS_RECEIPT", receipt.id(), actor.userId(), ledgerLines, reason));
        receipts.save(receipt);
        orders.saveAndFlush(po);

        audit.success(actor.userId(), storeId, "GOODS_RECEIVED", "INVENTORY", "GOODS_RECEIPT", receipt.id(),
                Map.of("poStatus", "pre-receipt"), Map.of("poStatus", po.status().name(), "transaction",
                        tx.number()), reason);

        return toResponse(receipt, po, ingMap);
    }

    // ------------------------------------------------------------- helpers

    private Map<UUID, Ingredient> loadIngredients(List<ReceiveGoodsRequest.Line> lines) {
        List<UUID> ids = lines.stream().map(ReceiveGoodsRequest.Line::ingredientId).distinct().collect(Collectors.toList());
        if (ids.isEmpty()) {
            return Map.of();
        }
        return ingredients.findAllById(ids).stream().collect(Collectors.toMap(Ingredient::id, Function.identity()));
    }

    private GoodsReceiptResponse toResponse(GoodsReceipt receipt, PurchaseOrder po, Map<UUID, Ingredient> ingMap) {
        List<GoodsReceiptLineResponse> lineResponses = receipt.lines().stream().map(l -> {
            Ingredient i = ingMap.get(l.ingredientId());
            return new GoodsReceiptLineResponse(l.id(), l.ingredientId(), i == null ? null : i.code(),
                    i == null ? null : i.name(), l.quantity(), l.unitCost(), l.lotNumber(), l.expiryDate());
        }).toList();
        return new GoodsReceiptResponse(receipt.id(), receipt.number(), receipt.purchaseOrderId(), po.number(),
                receipt.storeId(), receipt.receivedByUserId(), receipt.receivedAt(), lineResponses, receipt.version());
    }

    private record ReceiptPlan(UUID ingredientId, BigDecimal quantity, BigDecimal unitCost, String lotNumber,
            java.time.LocalDate expiryDate) {
    }
}
