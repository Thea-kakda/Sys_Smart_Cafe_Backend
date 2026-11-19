package com.smartcoffee.modules.inventory.application;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.modules.audit.application.AuditWriter;
import com.smartcoffee.modules.inventory.api.AdjustInventoryRequest;
import com.smartcoffee.modules.inventory.api.InventoryAdjustmentResponse;
import com.smartcoffee.modules.inventory.api.StockMovementResponse;
import com.smartcoffee.modules.inventory.domain.InventoryBalance;
import com.smartcoffee.modules.inventory.domain.StockTransaction;
import com.smartcoffee.modules.inventory.domain.StockTransactionLine;
import com.smartcoffee.modules.inventory.domain.StockTransactionType;
import com.smartcoffee.modules.inventory.persistence.IngredientRepository;
import com.smartcoffee.modules.inventory.persistence.InventoryBalanceRepository;
import com.smartcoffee.modules.inventory.persistence.StockMovementQueryRepository;
import com.smartcoffee.modules.inventory.persistence.StockMovementQueryRepository.StockMovementRow;
import com.smartcoffee.modules.inventory.persistence.StockTransactionRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Manual inventory writes (Full-stack wiring §1.5). Adjustments mutate
 * balances ONLY through the guarded native statements on
 * InventoryBalanceRepository (never via entity setters), write one
 * StockTransaction ledger row per command, and are audited with the mandatory
 * reason. The OUT guard (current - q >= reserved) means stock committed to
 * live reservations can never be adjusted away; a lost race surfaces as 409
 * INSUFFICIENT_STOCK rather than a negative balance. Movements is the
 * paginated line-level ledger read used by POS and the dashboard tables.
 */
@Service
public class InventoryAdjustmentService {

    private static final List<String> MOVEMENT_TYPES = List.of(
            "STOCK_IN", "ADJUSTMENT", "SALE_CONSUMPTION", "RETURN", "DAMAGED", "PURCHASE_RECEIPT",
            "TRANSFER_IN", "TRANSFER_OUT");

    private final InventoryBalanceRepository balances;
    private final IngredientRepository ingredients;
    private final StockTransactionRepository ledger;
    private final StockMovementQueryRepository movements;
    private final AuditWriter audit;
    private final FefoConsumptionService fefo;
    private final BatchService batches;

    public InventoryAdjustmentService(InventoryBalanceRepository b, IngredientRepository i,
            StockTransactionRepository l, StockMovementQueryRepository m, AuditWriter a, FefoConsumptionService fefo,
            BatchService batches) {
        balances = b;
        ingredients = i;
        ledger = l;
        movements = m;
        audit = a;
        this.fefo = fefo;
        this.batches = batches;
    }

    @Transactional
    public InventoryAdjustmentResponse adjust(CurrentActor actor, UUID storeId, AdjustInventoryRequest req) {
        var ingredient = ingredients.findById(req.ingredientId())
                .orElseThrow(() -> new ResourceNotFoundException("INGREDIENT_NOT_FOUND", "Ingredient not found"));
        if (!ingredient.active())
            throw new ValidationException("Ingredient is inactive");
        StockTransactionType type = parseType(req.type() == null ? "ADJUSTMENT" : req.type());
        boolean in = "IN".equals(req.direction().toUpperCase(Locale.ROOT));
        BigDecimal qty = req.quantity();
        String reason = req.reason().trim();

        balances.ensureRow(storeId, ingredient.id()); // no-op when the row exists
        BigDecimal before = balances.find(storeId, ingredient.id()).orElseThrow().currentStock();

        int updated = in ? balances.adjustUp(storeId, ingredient.id(), qty)
                : balances.adjustDown(storeId, ingredient.id(), qty);
        if (updated != 1)
            throw new ConflictException("INSUFFICIENT_STOCK",
                    "Adjustment would drive stock negative or into reserved territory");

        if (in && req.unitCost() != null)
            balances.restateAverageCost(storeId, ingredient.id(), req.unitCost());

        InventoryBalance after = balances.find(storeId, ingredient.id()).orElseThrow();
        BigDecimal delta = in ? qty : qty.negate();
        // M3: for a batch-tracked ingredient, mirror the aggregate movement onto
        // the lots. A stock-in grows an UNLOTTED lot; an OUT draws FEFO lots and
        // stamps each on its own ledger line. Untracked ingredients keep the
        // single batch-less line exactly as before.
        List<StockTransactionLine> lines;
        if (ingredient.trackBatches() && !in) {
            lines = fefo.drawLines(storeId, ingredient.id(), qty, before);
        } else {
            if (ingredient.trackBatches()) {
                batches.onReceipt(storeId, ingredient, null, null, qty, req.unitCost());
            }
            lines = List.of(new StockTransactionLine(ingredient.id(), delta, req.unitCost(), before,
                    after.currentStock()));
        }
        StockTransaction tx = ledger.save(StockTransaction.create(storeId, type, null, null, actor.userId(),
                lines, reason));

        audit.success(actor.userId(), storeId, "INVENTORY_ADJUSTED", "INVENTORY", "STOCK_TRANSACTION", tx.id(),
                Map.of("currentStock", before, "type", type.name()),
                Map.of("currentStock", after.currentStock(), "type", type.name(), "delta", delta.toPlainString()),
                reason + " | ingredient=" + ingredient.code());

        return new InventoryAdjustmentResponse(tx.id(), tx.number(), storeId, ingredient.id(), type.name(),
                in ? "IN" : "OUT", qty, before, after.currentStock(), after.averageUnitCost(), reason,
                after.version());
    }

    @Transactional(readOnly = true)
    public Page<StockMovementResponse> movements(UUID storeId, String type, UUID ingredientId, int page, int size) {
        int safePage = Math.max(page, 0);
        int safeSize = Math.min(Math.max(size, 1), 100);
        boolean typeFilter = type != null && !type.isBlank();
        String typeValue = (type != null && !type.isBlank()) ? type.toUpperCase(Locale.ROOT) : "";
        if (typeFilter && !MOVEMENT_TYPES.contains(typeValue))
            throw new ValidationException("Unknown movement type: " + type);
        boolean ingredientFilter = ingredientId != null;
        Page<StockMovementRow> rows = movements.movements(storeId, typeFilter,
                typeValue, ingredientFilter,
                ingredientFilter ? ingredientId : new UUID(0, 0), PageRequest.of(safePage, safeSize));
        List<StockMovementResponse> content = rows.getContent().stream()
                .map(r -> new StockMovementResponse(r.getTransactionId(), r.getTransactionNumber(),
                        r.getTransactionType(), r.getReferenceType(), r.getReferenceId(), r.getActorId(),
                        r.getReason(), r.getOccurredAt(), r.getIngredientId(), r.getIngredientCode(),
                        r.getIngredientName(), r.getUnitCode(), r.getDelta(), r.getUnitCost(),
                        r.getBalanceBefore(), r.getBalanceAfter()))
                .toList();
        return new PageImpl<>(content, rows.getPageable(), rows.getTotalElements());
    }

    private StockTransactionType parseType(String raw) {
        StockTransactionType t;
        try {
            t = StockTransactionType.valueOf(raw.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ValidationException("Unknown adjustment type: " + raw);
        }
        if (t == StockTransactionType.SALE_CONSUMPTION)
            throw new ValidationException("SALE_CONSUMPTION is system-generated");
        return t;
    }
}
