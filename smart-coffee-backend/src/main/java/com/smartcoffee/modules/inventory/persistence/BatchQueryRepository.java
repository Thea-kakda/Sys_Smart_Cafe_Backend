package com.smartcoffee.modules.inventory.persistence;

import com.smartcoffee.modules.inventory.domain.InventoryBatch;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Read model for the batch/expiry dashboard (V038, M3). Native cross-aggregate
 * read over {@code inventory.inventory_batches} joined to ingredient + unit
 * labels, following the same precedent as {@link LowStockQueryRepository} and
 * {@link StockMovementQueryRepository} (deliberately read-only; every write
 * still goes through {@link InventoryBatchRepository}). Filters use sentinels
 * ({@code :ingredientFilter}/{@code :expiringFilter} booleans + a zero UUID /
 * {@code integer} day window) to sidestep the Hibernate null-parameter
 * inference problem. Results are FEFO-ordered (earliest expiry first).
 */
public interface BatchQueryRepository extends Repository<InventoryBatch, UUID> {

    @Query(value = """
            select b.batch_id as batchId, b.store_id as storeId, b.ingredient_id as ingredientId,
                   i.ingredient_code as ingredientCode, i.ingredient_name as ingredientName,
                   u.unit_code as unitCode, b.lot_number as lotNumber, b.expiry_date as expiryDate,
                   b.quantity_base as quantityBase, b.unit_cost as unitCost, b.status as status,
                   b.received_at as receivedAt, b.version as version,
                   case when b.expiry_date is null then null
                        else (b.expiry_date - current_date) end as daysToExpiry
            from inventory.inventory_batches b
            join inventory.ingredients i on i.ingredient_id = b.ingredient_id
            join inventory.units_of_measure u on u.unit_id = i.base_unit_id
            where b.store_id = cast(:storeId as uuid)
              and (:ingredientFilter = false or b.ingredient_id = cast(:ingredientId as uuid))
              and (:expiringFilter = false
                   or (b.status = 'ACTIVE' and b.expiry_date is not null
                       and b.expiry_date <= current_date + cast(:expiringDays as integer)))
            order by b.status, b.expiry_date asc nulls last, b.received_at asc, b.batch_id
            """, nativeQuery = true)
    List<BatchRow> batches(@Param("storeId") UUID storeId,
            @Param("ingredientFilter") boolean ingredientFilter, @Param("ingredientId") UUID ingredientId,
            @Param("expiringFilter") boolean expiringFilter, @Param("expiringDays") int expiringDays);

    /** Interface projection for a batch row (labels resolved via the joins). */
    interface BatchRow {
        UUID getBatchId();

        UUID getStoreId();

        UUID getIngredientId();

        String getIngredientCode();

        String getIngredientName();

        String getUnitCode();

        String getLotNumber();

        LocalDate getExpiryDate();

        BigDecimal getQuantityBase();

        BigDecimal getUnitCost();

        String getStatus();

        Instant getReceivedAt();

        long getVersion();

        Integer getDaysToExpiry();
    }
}
