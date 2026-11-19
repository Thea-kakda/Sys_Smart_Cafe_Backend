package com.smartcoffee.modules.inventory.persistence;

import com.smartcoffee.modules.inventory.domain.InventoryBalance;
import com.smartcoffee.modules.inventory.domain.InventoryBalanceId;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Read model for low-stock / reorder planning (M2). Native cross-aggregate read
 * over {@code inventory.inventory_balances} joined to ingredient + unit labels,
 * same precedent as {@link StockMovementQueryRepository} (deliberately
 * read-only; every write still goes through the owning JPA repositories).
 *
 * <p>A row is "low" when its available stock ({@code current_stock -
 * reserved_stock}) is at or below the configured {@code reorder_point}
 * (V036). Rows with an unset plan ({@code reorder_point <= 0}) are never
 * flagged, so the feature stays opt-in per balance. The suggested quantity is
 * {@code max_stock - current_stock} clamped at zero (the caller turns a
 * positive suggestion into a draft purchase order line).
 */
public interface LowStockQueryRepository extends Repository<InventoryBalance, InventoryBalanceId> {

    @Query(value = """
            select b.ingredient_id as ingredientId, i.ingredient_code as ingredientCode,
                   i.ingredient_name as ingredientName, u.unit_code as unitCode,
                   b.current_stock as currentStock, b.reserved_stock as reservedStock,
                   (b.current_stock - b.reserved_stock) as availableStock,
                   b.reorder_point as reorderPoint, b.max_stock as maxStock,
                   b.average_unit_cost as averageUnitCost,
                   greatest(b.max_stock - b.current_stock, 0) as suggestedQuantity
            from inventory.inventory_balances b
            join inventory.ingredients i on i.ingredient_id = b.ingredient_id
            join inventory.units_of_measure u on u.unit_id = i.base_unit_id
            where b.store_id = cast(:storeId as uuid)
              and i.active = true
              and b.reorder_point > 0
              and (b.current_stock - b.reserved_stock) <= b.reorder_point
            order by (b.current_stock - b.reserved_stock) asc, i.ingredient_code
            """, nativeQuery = true)
    List<LowStockRow> lowStock(@Param("storeId") UUID storeId);

    interface LowStockRow {
        UUID getIngredientId();

        String getIngredientCode();

        String getIngredientName();

        String getUnitCode();

        BigDecimal getCurrentStock();

        BigDecimal getReservedStock();

        BigDecimal getAvailableStock();

        BigDecimal getReorderPoint();

        BigDecimal getMaxStock();

        BigDecimal getAverageUnitCost();

        BigDecimal getSuggestedQuantity();
    }
}
