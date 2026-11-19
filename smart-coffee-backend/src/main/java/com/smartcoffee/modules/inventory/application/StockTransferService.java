package com.smartcoffee.modules.inventory.application;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.common.security.StoreAuthorizationService;
import com.smartcoffee.modules.audit.application.AuditWriter;
import com.smartcoffee.modules.inventory.api.CreateStockTransferRequest;
import com.smartcoffee.modules.inventory.api.StockTransferResponse;
import com.smartcoffee.modules.inventory.domain.*;
import com.smartcoffee.modules.inventory.persistence.IngredientRepository;
import com.smartcoffee.modules.inventory.persistence.InventoryBalanceRepository;
import com.smartcoffee.modules.inventory.persistence.StockTransactionRepository;
import com.smartcoffee.modules.inventory.persistence.StockTransferRepository;
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
 * Inter-branch stock transfer lifecycle (request -> dispatch -> receive, or
 * cancel). Guarded by {@code inventory:transfer}; BOTH the source and the
 * destination store must be within the actor's scope for every action.
 *
 * <p>Physical stock only ever moves through the guarded native mutations on
 * {@link InventoryBalanceRepository}, exactly like goods receiving and manual
 * adjustments — never a setter, never a silent/negative balance. Dispatch draws
 * down the SOURCE ({@code adjustDown}, which cannot dip below committed
 * reservations) and stamps one {@code TRANSFER_OUT} ledger transaction; receive
 * opens/increments the DEST ({@code ensureRow + adjustUp}) and restates its
 * moving average to the shipped cost, stamping one {@code TRANSFER_IN}. Each
 * action is a single {@code @Transactional} command, writes exactly one ledger
 * transaction with real balance_before/after, and is audited.
 */
@SuppressWarnings("null")
@Service
public class StockTransferService {

    private final StockTransferRepository transfers;
    private final IngredientRepository ingredients;
    private final InventoryBalanceRepository balances;
    private final StockTransactionRepository ledger;
    private final AuditWriter audit;
    private final PermissionEvaluator permissions;
    private final StoreAuthorizationService stores;
    private final StockTransferMapper mapper;
    private final FefoConsumptionService fefo;
    private final BatchService batches;

    public StockTransferService(StockTransferRepository transfers, IngredientRepository ingredients,
            InventoryBalanceRepository balances, StockTransactionRepository ledger, AuditWriter audit,
            PermissionEvaluator permissions, StoreAuthorizationService stores, StockTransferMapper mapper,
            FefoConsumptionService fefo, BatchService batches) {
        this.transfers = transfers;
        this.ingredients = ingredients;
        this.balances = balances;
        this.ledger = ledger;
        this.audit = audit;
        this.permissions = permissions;
        this.stores = stores;
        this.mapper = mapper;
        this.fefo = fefo;
        this.batches = batches;
    }

    @Transactional
    public StockTransferResponse request(CurrentActor actor, CreateStockTransferRequest req) {
        permissions.require(actor, PermissionCodes.INVENTORY_TRANSFER);
        if (req.sourceStoreId().equals(req.destStoreId())) {
            throw new ValidationException("SAME_STORE", "Source and destination stores must differ");
        }
        stores.requireStore(actor, req.sourceStoreId());
        stores.requireStore(actor, req.destStoreId());

        List<UUID> ingredientIds = req.lines().stream().map(CreateStockTransferRequest.Line::ingredientId).toList();
        if (ingredientIds.stream().distinct().count() != ingredientIds.size()) {
            throw new ValidationException("DUPLICATE_LINE", "An ingredient appears on more than one line");
        }
        Map<UUID, Ingredient> ingMap = loadIngredients(ingredientIds);
        List<StockTransferLine> lines = new ArrayList<>();
        for (var l : req.lines()) {
            Ingredient ingredient = ingMap.get(l.ingredientId());
            if (ingredient == null) {
                throw new ResourceNotFoundException("INGREDIENT_NOT_FOUND", "Ingredient not found");
            }
            if (!ingredient.active()) {
                throw new ValidationException("Ingredient is inactive: " + ingredient.code());
            }
            lines.add(StockTransferLine.create(l.ingredientId(), l.quantity(), l.unitCost()));
        }

        StockTransfer transfer = StockTransfer.request(req.sourceStoreId(), req.destStoreId(), req.notes(),
                actor.userId(), lines);
        transfers.saveAndFlush(transfer);

        StockTransferResponse response = mapper.toResponse(transfer);
        audit.success(actor.userId(), req.sourceStoreId(), "STOCK_TRANSFER_REQUESTED", "INVENTORY", "STOCK_TRANSFER",
                transfer.id(), null, response, "Requested transfer " + transfer.number());
        return response;
    }

    @Transactional
    public StockTransferResponse dispatch(CurrentActor actor, UUID transferId) {
        permissions.require(actor, PermissionCodes.INVENTORY_TRANSFER);
        StockTransfer transfer = transfers.findByIdForUpdate(transferId)
                .orElseThrow(() -> new ResourceNotFoundException("STOCK_TRANSFER_NOT_FOUND", "Stock transfer not found"));
        stores.requireStore(actor, transfer.sourceStoreId());
        stores.requireStore(actor, transfer.destStoreId());

        transition(transfer::dispatch);

        // Draw the stock out of the source; the guarded adjustDown refuses to
        // breach reservations/zero, so a lost race rolls the whole command back.
        Map<UUID, Boolean> tracked = trackBatchesMap(transfer.lines());
        List<StockTransactionLine> ledgerLines = new ArrayList<>();
        for (StockTransferLine line : transfer.lines()) {
            UUID ingredientId = line.ingredientId();
            BigDecimal before = balances.find(transfer.sourceStoreId(), ingredientId)
                    .map(InventoryBalance::currentStock).orElseThrow(() -> new ConflictException("INSUFFICIENT_STOCK",
                            "No stock at the source store to dispatch"));
            int updated = balances.adjustDown(transfer.sourceStoreId(), ingredientId, line.quantity());
            if (updated != 1) {
                throw new ConflictException("INSUFFICIENT_STOCK",
                        "Source store does not have enough uncommitted stock to dispatch");
            }
            if (Boolean.TRUE.equals(tracked.get(ingredientId))) {
                // M3: draw the shipped quantity across the source's FEFO lots.
                ledgerLines.addAll(fefo.drawLines(transfer.sourceStoreId(), ingredientId, line.quantity(), before));
            } else {
                BigDecimal after = balances.find(transfer.sourceStoreId(), ingredientId).orElseThrow().currentStock();
                ledgerLines.add(new StockTransactionLine(ingredientId, line.quantity().negate(), line.unitCost(), before,
                        after));
            }
        }

        String reason = "Dispatched " + transfer.number() + " to store " + transfer.destStoreId();
        StockTransaction tx = ledger.save(StockTransaction.create(transfer.sourceStoreId(),
                StockTransactionType.TRANSFER_OUT, "STOCK_TRANSFER", transfer.id(), actor.userId(), ledgerLines,
                reason));
        transfers.saveAndFlush(transfer);

        StockTransferResponse response = mapper.toResponse(transfer);
        audit.success(actor.userId(), transfer.sourceStoreId(), "STOCK_TRANSFER_DISPATCHED", "INVENTORY",
                "STOCK_TRANSFER", transfer.id(), null, response,
                reason + " (ledger " + tx.number() + ")");
        return response;
    }

    @Transactional
    public StockTransferResponse receive(CurrentActor actor, UUID transferId) {
        permissions.require(actor, PermissionCodes.INVENTORY_TRANSFER);
        StockTransfer transfer = transfers.findByIdForUpdate(transferId)
                .orElseThrow(() -> new ResourceNotFoundException("STOCK_TRANSFER_NOT_FOUND", "Stock transfer not found"));
        stores.requireStore(actor, transfer.sourceStoreId());
        stores.requireStore(actor, transfer.destStoreId());

        transition(transfer::receive);

        // Bring the stock into the destination and restate its moving average to
        // the shipped cost — same valued stock-in shape as goods receiving.
        Map<UUID, Boolean> tracked = trackBatchesMap(transfer.lines());
        List<StockTransactionLine> ledgerLines = new ArrayList<>();
        for (StockTransferLine line : transfer.lines()) {
            UUID ingredientId = line.ingredientId();
            balances.ensureRow(transfer.destStoreId(), ingredientId);
            BigDecimal before = balances.find(transfer.destStoreId(), ingredientId).orElseThrow().currentStock();
            int updated = balances.adjustUp(transfer.destStoreId(), ingredientId, line.quantity());
            if (updated != 1) {
                throw new ConflictException("INSUFFICIENT_STOCK", "Failed to open/update the balance row on receive");
            }
            balances.restateAverageCost(transfer.destStoreId(), ingredientId, line.unitCost());
            BigDecimal after = balances.find(transfer.destStoreId(), ingredientId).orElseThrow().currentStock();
            ledgerLines.add(new StockTransactionLine(ingredientId, line.quantity(), line.unitCost(), before, after));
            // M3: the shipment carries no lot/expiry, so grow an UNLOTTED lot at
            // the destination for a batch-tracked ingredient (no defaulted expiry).
            if (Boolean.TRUE.equals(tracked.get(ingredientId))) {
                batches.recordStockIn(transfer.destStoreId(), ingredientId, InventoryBatch.UNLOTTED, null,
                        line.quantity(), line.unitCost());
            }
        }

        String reason = "Received " + transfer.number() + " from store " + transfer.sourceStoreId();
        StockTransaction tx = ledger.save(StockTransaction.create(transfer.destStoreId(),
                StockTransactionType.TRANSFER_IN, "STOCK_TRANSFER", transfer.id(), actor.userId(), ledgerLines,
                reason));
        transfers.saveAndFlush(transfer);

        StockTransferResponse response = mapper.toResponse(transfer);
        audit.success(actor.userId(), transfer.destStoreId(), "STOCK_TRANSFER_RECEIVED", "INVENTORY",
                "STOCK_TRANSFER", transfer.id(), null, response, reason + " (ledger " + tx.number() + ")");
        return response;
    }

    @Transactional
    public StockTransferResponse cancel(CurrentActor actor, UUID transferId, String reason) {
        permissions.require(actor, PermissionCodes.INVENTORY_TRANSFER);
        StockTransfer transfer = transfers.findByIdForUpdate(transferId)
                .orElseThrow(() -> new ResourceNotFoundException("STOCK_TRANSFER_NOT_FOUND", "Stock transfer not found"));
        stores.requireStore(actor, transfer.sourceStoreId());
        stores.requireStore(actor, transfer.destStoreId());

        StockTransferResponse before = mapper.toResponse(transfer);
        // Cancelling an IN_TRANSIT transfer is allowed by the machine but would
        // strand the already-dispatched stock, so only a REQUESTED transfer may
        // be cancelled without a compensating movement.
        if (transfer.status() == StockTransferStatus.IN_TRANSIT) {
            throw new ConflictException("TRANSFER_IN_TRANSIT",
                    "An in-transit transfer cannot be cancelled; receive it or raise an adjustment");
        }
        transition(transfer::cancel);
        transfers.saveAndFlush(transfer);

        StockTransferResponse after = mapper.toResponse(transfer);
        audit.success(actor.userId(), transfer.sourceStoreId(), "STOCK_TRANSFER_CANCELLED", "INVENTORY",
                "STOCK_TRANSFER", transfer.id(), before, after,
                "Cancelled transfer " + transfer.number() + (reason == null ? "" : ": " + reason));
        return after;
    }

    // ------------------------------------------------------------- helpers

    private Map<UUID, Ingredient> loadIngredients(List<UUID> ids) {
        List<UUID> distinct = ids.stream().distinct().collect(Collectors.toList());
        if (distinct.isEmpty()) {
            return Map.of();
        }
        return ingredients.findAllById(distinct).stream().collect(Collectors.toMap(Ingredient::id, Function.identity()));
    }

    /** track_batches flag per line ingredient, for the FEFO lot wire-in (M3). */
    private Map<UUID, Boolean> trackBatchesMap(List<StockTransferLine> lines) {
        Map<UUID, Boolean> map = new java.util.HashMap<>();
        for (Ingredient i : loadIngredients(lines.stream().map(StockTransferLine::ingredientId).toList()).values()) {
            map.put(i.id(), i.trackBatches());
        }
        return map;
    }

    private void transition(Runnable action) {
        try {
            action.run();
        } catch (IllegalStateException e) {
            throw new ConflictException("INVALID_STATUS_TRANSITION", e.getMessage());
        }
    }
}
