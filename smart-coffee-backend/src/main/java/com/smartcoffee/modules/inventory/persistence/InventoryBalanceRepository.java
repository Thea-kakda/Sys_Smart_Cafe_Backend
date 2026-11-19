package com.smartcoffee.modules.inventory.persistence;

import com.smartcoffee.modules.inventory.domain.*;
import java.math.BigDecimal;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface InventoryBalanceRepository extends JpaRepository<InventoryBalance, InventoryBalanceId> {
    @Modifying
    @Query(value = "update inventory.inventory_balances set reserved_stock=reserved_stock+:q,updated_at=current_timestamp,version=version+1 where store_id=:s and ingredient_id=:i and current_stock-reserved_stock>=:q", nativeQuery = true)
    int reserve(@Param("s") UUID store, @Param("i") UUID ingredient, @Param("q") BigDecimal quantity);

    @Modifying
    @Query(value = "update inventory.inventory_balances set reserved_stock=reserved_stock-:q,updated_at=current_timestamp,version=version+1 where store_id=:s and ingredient_id=:i and reserved_stock>=:q", nativeQuery = true)
    int release(@Param("s") UUID store, @Param("i") UUID ingredient, @Param("q") BigDecimal quantity);

    @Modifying
    @Query(value = "update inventory.inventory_balances set current_stock=current_stock-:q,reserved_stock=reserved_stock-:q,updated_at=current_timestamp,version=version+1 where store_id=:s and ingredient_id=:i and current_stock>=:q and reserved_stock>=:q", nativeQuery = true)
    int consume(@Param("s") UUID store, @Param("i") UUID ingredient, @Param("q") BigDecimal quantity);

    /**
     * Manual increase (Full-stack wiring §1.5). No guard needed beyond the
     * DB check constraints: current stock can only grow.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "update inventory.inventory_balances set current_stock=current_stock+:q,updated_at=current_timestamp,version=version+1 where store_id=:s and ingredient_id=:i", nativeQuery = true)
    int adjustUp(@Param("s") UUID store, @Param("i") UUID ingredient, @Param("q") BigDecimal quantity);

    /**
     * Manual decrease (§1.5). The guard keeps the invariant
     * reserved_stock &lt;= current_stock after the update — stock reserved by
     * live orders cannot be adjusted away; 0 rows means the caller lost the
     * race or asked for more than is uncommitted.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "update inventory.inventory_balances set current_stock=current_stock-:q,updated_at=current_timestamp,version=version+1 where store_id=:s and ingredient_id=:i and current_stock-:q>=reserved_stock", nativeQuery = true)
    int adjustDown(@Param("s") UUID store, @Param("i") UUID ingredient, @Param("q") BigDecimal quantity);

    /**
     * Opens a zero row for an ingredient the store has never tracked, so the
     * adjustment update path stays single-shaped (ON CONFLICT DO NOTHING:
     * never clobbers an existing balance under concurrency).
     */
    @Modifying
    @Query(value = "insert into inventory.inventory_balances (store_id, ingredient_id) values (:s, :i) on conflict (store_id, ingredient_id) do nothing", nativeQuery = true)
    int ensureRow(@Param("s") UUID store, @Param("i") UUID ingredient);

    /**
     * Moving-average cost restatement on a valued stock-in (§1.5). Full
     * weighted-average math is a procurement concern; the operator states the
     * effective cost explicitly, so no silent reinterpretation happens here.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = "update inventory.inventory_balances set average_unit_cost=:c,updated_at=current_timestamp,version=version+1 where store_id=:s and ingredient_id=:i", nativeQuery = true)
    int restateAverageCost(@Param("s") UUID store, @Param("i") UUID ingredient, @Param("c") BigDecimal cost);

    @Query(value = "select * from inventory.inventory_balances where store_id=:s order by ingredient_id", nativeQuery = true)
    List<InventoryBalance> byStore(@Param("s") UUID store);

    /**
     * Availability engine (Slice 3A) batch read: the balances for one store across
     * a specific set of ingredients, in ONE query and deterministically ordered by
     * ingredient id. An ingredient with no row here simply has no balance record and
     * is treated by the reader as zero available (never fabricated).
     */
    @Query(value = "select * from inventory.inventory_balances where store_id=:s "
            + "and ingredient_id in (:ingredients) order by ingredient_id", nativeQuery = true)
    List<InventoryBalance> byStoreAndIngredients(@Param("s") UUID store,
            @Param("ingredients") Collection<UUID> ingredients);

    /** Point read for the adjustment command (before/after snapshot around the native update). */
    @Query(value = "select * from inventory.inventory_balances where store_id=:s and ingredient_id=:i", nativeQuery = true)
    Optional<InventoryBalance> find(@Param("s") UUID store, @Param("i") UUID ingredient);
}

