package com.smartcoffee.modules.inventory.persistence;

import com.smartcoffee.modules.inventory.domain.StockTransaction;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

/**
 * Read model for the stock movement ledger (Full-stack wiring §1.5): one row
 * per transaction LINE (the atom operators and reports reason about), joined
 * transitively to ingredient and unit labels. Same precedent as
 * StaffQueryRepository / UserDirectoryQueryRepository — cross-aggregate reads
 * are native SQL, sentinel parameters (empty string = no filter) avoid the
 * Hibernate 7 / pgjdbc null-parameter inference problem, and every write
 * still goes through the owning JPA repositories. Deliberately read-only.
 */
public interface StockMovementQueryRepository extends Repository<StockTransaction, UUID> {

    @Query(value = """
            select t.stock_transaction_id as transactionId, t.transaction_number as transactionNumber,
                   t.transaction_type as transactionType, t.reference_type as referenceType,
                   t.reference_id as referenceId, t.recorded_by_user_id as actorId,
                   t.reason as reason, t.occurred_at as occurredAt,
                   l.ingredient_id as ingredientId, i.ingredient_code as ingredientCode,
                   i.ingredient_name as ingredientName, u.unit_code as unitCode,
                   l.quantity_delta as delta, l.unit_cost as unitCost,
                   l.balance_before as balanceBefore, l.balance_after as balanceAfter
            from inventory.stock_transactions t
            join inventory.stock_transaction_lines l on l.stock_transaction_id = t.stock_transaction_id
            join inventory.ingredients i on i.ingredient_id = l.ingredient_id
            join inventory.units_of_measure u on u.unit_id = i.base_unit_id
            where t.store_id = cast(:storeId as uuid)
              and (:typeFilter = false or t.transaction_type = :type)
              and (:ingredientFilter = false or l.ingredient_id = cast(:ingredientId as uuid))
            order by t.occurred_at desc, t.stock_transaction_id desc, l.stock_transaction_line_id
            """, countQuery = """
            select count(*)
            from inventory.stock_transactions t
            join inventory.stock_transaction_lines l on l.stock_transaction_id = t.stock_transaction_id
            where t.store_id = cast(:storeId as uuid)
              and (:typeFilter = false or t.transaction_type = :type)
              and (:ingredientFilter = false or l.ingredient_id = cast(:ingredientId as uuid))
            """, nativeQuery = true)
    Page<StockMovementRow> movements(@Param("storeId") UUID storeId,
            @Param("typeFilter") boolean typeFilter, @Param("type") String type,
            @Param("ingredientFilter") boolean ingredientFilter, @Param("ingredientId") UUID ingredientId,
            Pageable pageable);

    /** Interface projection (Instant mapping proven by the staff/user directory reads). */
    interface StockMovementRow {
        UUID getTransactionId();

        String getTransactionNumber();

        String getTransactionType();

        String getReferenceType();

        UUID getReferenceId();

        UUID getActorId();

        String getReason();

        Instant getOccurredAt();

        UUID getIngredientId();

        String getIngredientCode();

        String getIngredientName();

        String getUnitCode();

        BigDecimal getDelta();

        BigDecimal getUnitCost();

        BigDecimal getBalanceBefore();

        BigDecimal getBalanceAfter();
    }
}
