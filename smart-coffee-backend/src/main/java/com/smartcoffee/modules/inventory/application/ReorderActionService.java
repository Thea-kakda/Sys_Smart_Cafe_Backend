package com.smartcoffee.modules.inventory.application;

import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.common.security.StoreAuthorizationService;
import com.smartcoffee.modules.inventory.api.CreatePurchaseOrderRequest;
import com.smartcoffee.modules.inventory.api.PurchaseOrderResponse;
import com.smartcoffee.modules.inventory.api.RequestReorderRequest;
import com.smartcoffee.modules.inventory.domain.Ingredient;
import com.smartcoffee.modules.inventory.persistence.IngredientRepository;
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
 * Turns a chosen set of reorder lines into a DRAFT purchase order (M2), guarded
 * by {@code inventory:reorder} plus the store scope. It is a thin orchestration
 * over {@link PurchaseOrderCommandService} — the PO is created in DRAFT, so no
 * stock or money moves until the operator submits and receives it through the
 * normal purchasing flow. A line without an explicit cost defaults to the
 * ingredient's standard cost.
 */
@SuppressWarnings("null")
@Service
public class ReorderActionService {

    private final PurchaseOrderCommandService purchaseOrders;
    private final IngredientRepository ingredients;
    private final PermissionEvaluator permissions;
    private final StoreAuthorizationService stores;

    public ReorderActionService(PurchaseOrderCommandService purchaseOrders, IngredientRepository ingredients,
            PermissionEvaluator permissions, StoreAuthorizationService stores) {
        this.purchaseOrders = purchaseOrders;
        this.ingredients = ingredients;
        this.permissions = permissions;
        this.stores = stores;
    }

    @Transactional
    public PurchaseOrderResponse draftPurchaseOrder(CurrentActor actor, UUID storeId, RequestReorderRequest req) {
        permissions.require(actor, PermissionCodes.INVENTORY_REORDER);
        stores.requireStore(actor, storeId);

        List<UUID> ingredientIds = req.lines().stream().map(RequestReorderRequest.Line::ingredientId).toList();
        Map<UUID, Ingredient> ingMap = ingredientIds.isEmpty() ? Map.of()
                : ingredients.findAllById(ingredientIds).stream()
                        .collect(Collectors.toMap(Ingredient::id, Function.identity()));

        List<CreatePurchaseOrderRequest.Line> poLines = new ArrayList<>();
        for (var l : req.lines()) {
            Ingredient ingredient = ingMap.get(l.ingredientId());
            if (ingredient == null) {
                throw new ResourceNotFoundException("INGREDIENT_NOT_FOUND", "Ingredient not found");
            }
            BigDecimal cost = l.unitCost() != null ? l.unitCost() : ingredient.standardCost();
            poLines.add(new CreatePurchaseOrderRequest.Line(l.ingredientId(), l.quantity(), cost));
        }

        CreatePurchaseOrderRequest create = new CreatePurchaseOrderRequest(req.supplierId(), req.expectedAt(),
                req.notes(), poLines);
        return purchaseOrders.create(actor, storeId, create);
    }
}
