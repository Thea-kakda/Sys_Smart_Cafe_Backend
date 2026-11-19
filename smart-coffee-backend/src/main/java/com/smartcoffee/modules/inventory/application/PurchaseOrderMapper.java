package com.smartcoffee.modules.inventory.application;

import com.smartcoffee.modules.inventory.api.PurchaseOrderLineResponse;
import com.smartcoffee.modules.inventory.api.PurchaseOrderResponse;
import com.smartcoffee.modules.inventory.domain.*;
import com.smartcoffee.modules.inventory.persistence.*;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maps purchase-order domain objects to their API responses, enriching lines
 * with ingredient + base-unit labels. Batches ingredient/unit lookups to avoid
 * N+1 within the caller's read transaction.
 */
@SuppressWarnings("null")
@Component
public class PurchaseOrderMapper {

    private final IngredientRepository ingredients;
    private final UnitOfMeasureRepository units;
    private final SupplierRepository suppliers;

    public PurchaseOrderMapper(IngredientRepository ingredients, UnitOfMeasureRepository units,
            SupplierRepository suppliers) {
        this.ingredients = ingredients;
        this.units = units;
        this.suppliers = suppliers;
    }

    @Transactional(readOnly = true)
    public PurchaseOrderResponse toResponse(PurchaseOrder po) {
        var supplier = suppliers.findById(po.supplierId());
        return new PurchaseOrderResponse(po.id(), po.number(), po.storeId(), po.supplierId(),
                supplier.map(Supplier::code).orElse(null), supplier.map(Supplier::name).orElse(null),
                po.status().name(), po.orderedAt(), po.expectedAt(), po.notes(), po.createdByUserId(),
                lineResponses(po.lines()), po.createdAt(), po.updatedAt(), po.version());
    }

    private List<PurchaseOrderLineResponse> lineResponses(List<PurchaseOrderLine> lines) {
        Map<UUID, Ingredient> ing = loadIngredients(lines.stream().map(PurchaseOrderLine::ingredientId)
                .filter(Objects::nonNull).distinct().collect(Collectors.toList()));
        Map<UUID, String> unitCodes = units.findAllById(ing.values().stream().map(Ingredient::baseUnitId).distinct()
                .collect(Collectors.toList())).stream().collect(Collectors.toMap(UnitOfMeasure::id, UnitOfMeasure::code));
        return lines.stream().map(l -> {
            Ingredient i = ing.get(l.ingredientId());
            String unitCode = i == null ? null : unitCodes.get(i.baseUnitId());
            return new PurchaseOrderLineResponse(l.id(), l.ingredientId(), i == null ? null : i.code(),
                    i == null ? null : i.name(), unitCode, l.quantityOrdered(), l.quantityReceived(),
                    l.outstanding(), l.unitCost());
        }).toList();
    }

    private Map<UUID, Ingredient> loadIngredients(List<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return ingredients.findAllById(ids).stream()
                .collect(Collectors.toMap(Ingredient::id, Function.identity()));
    }
}
