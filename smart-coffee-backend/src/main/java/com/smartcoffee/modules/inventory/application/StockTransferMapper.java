package com.smartcoffee.modules.inventory.application;

import com.smartcoffee.modules.inventory.api.StockTransferLineResponse;
import com.smartcoffee.modules.inventory.api.StockTransferResponse;
import com.smartcoffee.modules.inventory.domain.Ingredient;
import com.smartcoffee.modules.inventory.domain.StockTransfer;
import com.smartcoffee.modules.inventory.domain.StockTransferLine;
import com.smartcoffee.modules.inventory.domain.UnitOfMeasure;
import com.smartcoffee.modules.inventory.persistence.IngredientRepository;
import com.smartcoffee.modules.inventory.persistence.UnitOfMeasureRepository;
import com.smartcoffee.modules.organization.persistence.StoreRepository;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maps stock-transfer domain objects to their API responses, enriching lines
 * with ingredient + base-unit labels and the header with source/dest store
 * names. Batches ingredient/unit/store lookups to avoid N+1 within the caller's
 * transaction. Mirrors {@link PurchaseOrderMapper}.
 */
@SuppressWarnings("null")
@Component
public class StockTransferMapper {

    private final IngredientRepository ingredients;
    private final UnitOfMeasureRepository units;
    private final StoreRepository stores;

    public StockTransferMapper(IngredientRepository ingredients, UnitOfMeasureRepository units,
            StoreRepository stores) {
        this.ingredients = ingredients;
        this.units = units;
        this.stores = stores;
    }

    @Transactional(readOnly = true)
    public StockTransferResponse toResponse(StockTransfer t) {
        String sourceName = stores.findById(t.sourceStoreId()).map(s -> s.name()).orElse(null);
        String destName = stores.findById(t.destStoreId()).map(s -> s.name()).orElse(null);
        return new StockTransferResponse(t.id(), t.number(), t.sourceStoreId(), sourceName, t.destStoreId(), destName,
                t.status().name(), t.notes(), t.requestedByUserId(), t.dispatchedAt(), t.receivedAt(),
                lineResponses(t.lines()), t.createdAt(), t.updatedAt(), t.version());
    }

    private List<StockTransferLineResponse> lineResponses(List<StockTransferLine> lines) {
        Map<UUID, Ingredient> ing = loadIngredients(lines.stream().map(StockTransferLine::ingredientId)
                .filter(Objects::nonNull).distinct().collect(Collectors.toList()));
        Map<UUID, String> unitCodes = units.findAllById(ing.values().stream().map(Ingredient::baseUnitId).distinct()
                .collect(Collectors.toList())).stream().collect(Collectors.toMap(UnitOfMeasure::id, UnitOfMeasure::code));
        return lines.stream().map(l -> {
            Ingredient i = ing.get(l.ingredientId());
            String unitCode = i == null ? null : unitCodes.get(i.baseUnitId());
            return new StockTransferLineResponse(l.id(), l.ingredientId(), i == null ? null : i.code(),
                    i == null ? null : i.name(), unitCode, l.quantity(), l.unitCost());
        }).toList();
    }

    private Map<UUID, Ingredient> loadIngredients(List<UUID> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        return ingredients.findAllById(ids).stream().collect(Collectors.toMap(Ingredient::id, Function.identity()));
    }
}
