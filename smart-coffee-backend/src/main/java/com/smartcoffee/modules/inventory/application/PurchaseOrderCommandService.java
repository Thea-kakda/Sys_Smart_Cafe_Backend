package com.smartcoffee.modules.inventory.application;

import com.smartcoffee.common.exception.ConflictException;
import com.smartcoffee.common.exception.ResourceNotFoundException;
import com.smartcoffee.common.exception.ValidationException;
import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.modules.audit.application.AuditWriter;
import com.smartcoffee.modules.inventory.api.CreatePurchaseOrderRequest;
import com.smartcoffee.modules.inventory.api.PurchaseOrderResponse;
import com.smartcoffee.modules.inventory.domain.*;
import com.smartcoffee.modules.inventory.persistence.IngredientRepository;
import com.smartcoffee.modules.inventory.persistence.PurchaseOrderRepository;
import com.smartcoffee.modules.inventory.persistence.SupplierRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Purchase-order lifecycle (create DRAFT -> submit -> cancel/close). Guarded by
 * {@code purchase:manage}; the store scope is enforced at the controller and
 * again here by the aggregate's own {@code storeId}. Validations: supplier and
 * every ingredient must exist (and be active for new orders), lines carry a
 * positive quantity and non-negative cost, and no ingredient repeats on one
 * order. State transitions run through the {@link PurchaseOrder} machine; an
 * illegal move maps to {@code 409}. No hard deletes — cancel/close only.
 */
@SuppressWarnings("null")
@Service
public class PurchaseOrderCommandService {

    private final PurchaseOrderRepository orders;
    private final SupplierRepository suppliers;
    private final IngredientRepository ingredients;
    private final AuditWriter audit;
    private final PermissionEvaluator permissions;
    private final PurchaseOrderMapper mapper;

    public PurchaseOrderCommandService(PurchaseOrderRepository orders, SupplierRepository suppliers,
            IngredientRepository ingredients, AuditWriter audit, PermissionEvaluator permissions,
            PurchaseOrderMapper mapper) {
        this.orders = orders;
        this.suppliers = suppliers;
        this.ingredients = ingredients;
        this.audit = audit;
        this.permissions = permissions;
        this.mapper = mapper;
    }

    @Transactional
    public PurchaseOrderResponse create(CurrentActor actor, UUID storeId, CreatePurchaseOrderRequest req) {
        permissions.require(actor, PermissionCodes.PURCHASE_MANAGE);

        Supplier supplier = suppliers.findById(req.supplierId())
                .orElseThrow(() -> new ResourceNotFoundException("SUPPLIER_NOT_FOUND", "Supplier not found"));
        if (!supplier.active()) {
            throw new ValidationException("SUPPLIER_INACTIVE", "Supplier is inactive");
        }

        List<UUID> ingredientIds = req.lines().stream().map(CreatePurchaseOrderRequest.Line::ingredientId).toList();
        if (ingredientIds.stream().distinct().count() != ingredientIds.size()) {
            throw new ValidationException("DUPLICATE_LINE", "An ingredient appears on more than one line");
        }
        List<PurchaseOrderLine> lines = new ArrayList<>();
        for (var l : req.lines()) {
            Ingredient ingredient = ingredients.findById(l.ingredientId())
                    .orElseThrow(() -> new ResourceNotFoundException("INGREDIENT_NOT_FOUND", "Ingredient not found"));
            if (!ingredient.active()) {
                throw new ValidationException("Ingredient is inactive: " + ingredient.code());
            }
            lines.add(PurchaseOrderLine.create(l.ingredientId(), l.quantityOrdered(), l.unitCost()));
        }

        PurchaseOrder po = PurchaseOrder.draft(storeId, supplier.id(), req.expectedAt(), req.notes(), actor.userId(),
                lines);
        orders.saveAndFlush(po);

        PurchaseOrderResponse response = mapper.toResponse(po);
        audit.success(actor.userId(), storeId, "PURCHASE_ORDER_CREATED", "INVENTORY", "PURCHASE_ORDER", po.id(), null,
                response, "Created purchase order " + po.number());
        return response;
    }

    @Transactional
    public PurchaseOrderResponse submit(CurrentActor actor, UUID storeId, UUID poId) {
        permissions.require(actor, PermissionCodes.PURCHASE_MANAGE);
        PurchaseOrder po = loadForStore(storeId, poId);
        transition(po::submit);
        orders.saveAndFlush(po);
        PurchaseOrderResponse response = mapper.toResponse(po);
        audit.success(actor.userId(), storeId, "PURCHASE_ORDER_SUBMITTED", "INVENTORY", "PURCHASE_ORDER", poId, null,
                response, "Submitted purchase order " + po.number());
        return response;
    }

    @Transactional
    public PurchaseOrderResponse cancel(CurrentActor actor, UUID storeId, UUID poId, String reason) {
        permissions.require(actor, PermissionCodes.PURCHASE_MANAGE);
        PurchaseOrder po = loadForStore(storeId, poId);
        PurchaseOrderResponse before = mapper.toResponse(po);
        transition(po::cancel);
        orders.saveAndFlush(po);
        PurchaseOrderResponse after = mapper.toResponse(po);
        audit.success(actor.userId(), storeId, "PURCHASE_ORDER_CANCELLED", "INVENTORY", "PURCHASE_ORDER", poId, before,
                after, "Cancelled purchase order " + po.number() + (reason == null ? "" : ": " + reason));
        return after;
    }

    @Transactional
    public PurchaseOrderResponse close(CurrentActor actor, UUID storeId, UUID poId) {
        permissions.require(actor, PermissionCodes.PURCHASE_MANAGE);
        PurchaseOrder po = loadForStore(storeId, poId);
        transition(po::close);
        orders.saveAndFlush(po);
        PurchaseOrderResponse response = mapper.toResponse(po);
        audit.success(actor.userId(), storeId, "PURCHASE_ORDER_CLOSED", "INVENTORY", "PURCHASE_ORDER", poId, null,
                response, "Closed purchase order " + po.number());
        return response;
    }

    // ------------------------------------------------------------- helpers

    private PurchaseOrder loadForStore(UUID storeId, UUID poId) {
        PurchaseOrder po = orders.findById(poId)
                .orElseThrow(() -> new ResourceNotFoundException("PURCHASE_ORDER_NOT_FOUND", "Purchase order not found"));
        if (!po.storeId().equals(storeId)) {
            throw new ConflictException("STORE_MISMATCH", "Purchase order belongs to a different store");
        }
        return po;
    }

    private void transition(Runnable action) {
        try {
            action.run();
        } catch (IllegalStateException e) {
            throw new ConflictException("INVALID_STATUS_TRANSITION", e.getMessage());
        }
    }
}
