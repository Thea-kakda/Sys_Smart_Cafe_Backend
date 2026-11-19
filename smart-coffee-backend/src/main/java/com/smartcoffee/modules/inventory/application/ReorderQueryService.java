package com.smartcoffee.modules.inventory.application;

import com.smartcoffee.common.security.CurrentActor;
import com.smartcoffee.common.security.PermissionCodes;
import com.smartcoffee.common.security.PermissionEvaluator;
import com.smartcoffee.common.security.StoreAuthorizationService;
import com.smartcoffee.modules.inventory.api.LowStockResponse;
import com.smartcoffee.modules.inventory.api.ReorderSuggestionResponse;
import com.smartcoffee.modules.inventory.persistence.LowStockQueryRepository;
import com.smartcoffee.modules.inventory.persistence.LowStockQueryRepository.LowStockRow;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read-only low-stock / reorder planning (M2). Guarded by {@code inventory:read}
 * plus the store scope. A balance is "low" when its available stock sits at or
 * below its configured reorder point; a suggestion sizes a replenishment back up
 * to the ceiling. Nothing here mutates stock or creates orders — drafting a
 * purchase order is the explicit {@link ReorderActionService} path.
 */
@Service
public class ReorderQueryService {

    private final LowStockQueryRepository lowStock;
    private final PermissionEvaluator permissions;
    private final StoreAuthorizationService stores;

    public ReorderQueryService(LowStockQueryRepository lowStock, PermissionEvaluator permissions,
            StoreAuthorizationService stores) {
        this.lowStock = lowStock;
        this.permissions = permissions;
        this.stores = stores;
    }

    @Transactional(readOnly = true)
    public List<LowStockResponse> lowStock(CurrentActor actor, UUID storeId) {
        permissions.require(actor, PermissionCodes.INVENTORY_READ);
        stores.requireStore(actor, storeId);
        return lowStock.lowStock(storeId).stream().map(ReorderQueryService::toLowStock).toList();
    }

    @Transactional(readOnly = true)
    public List<ReorderSuggestionResponse> reorderSuggestions(CurrentActor actor, UUID storeId) {
        permissions.require(actor, PermissionCodes.INVENTORY_READ);
        stores.requireStore(actor, storeId);
        return lowStock.lowStock(storeId).stream().map(ReorderQueryService::toSuggestion).toList();
    }

    private static LowStockResponse toLowStock(LowStockRow r) {
        return new LowStockResponse(r.getIngredientId(), r.getIngredientCode(), r.getIngredientName(),
                r.getUnitCode(), r.getCurrentStock(), r.getReservedStock(), r.getAvailableStock(),
                r.getReorderPoint(), r.getMaxStock(), r.getAverageUnitCost());
    }

    private static ReorderSuggestionResponse toSuggestion(LowStockRow r) {
        BigDecimal suggested = r.getSuggestedQuantity() == null ? BigDecimal.ZERO : r.getSuggestedQuantity();
        return new ReorderSuggestionResponse(r.getIngredientId(), r.getIngredientCode(), r.getIngredientName(),
                r.getUnitCode(), r.getAvailableStock(), r.getReorderPoint(), r.getMaxStock(), suggested);
    }
}
