package com.smartcoffee.modules.inventory.persistence;

import com.smartcoffee.modules.inventory.domain.InventoryBatch;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Batch/lot persistence (V038). Reads that feed a mutation lock the rows
 * {@code PESSIMISTIC_WRITE} so the FEFO draw and the aggregate guard cannot
 * interleave with a concurrent consumption of the same lot; every quantity
 * change is applied only through a guarded native statement (never an entity
 * setter) so a lost race updates 0 rows and the caller rolls back rather than
 * driving a batch negative. Deliberately excludes the read-model listing, which
 * lives in {@link BatchQueryRepository}.
 */
public interface InventoryBatchRepository extends JpaRepository<InventoryBatch, UUID> {

    /**
     * FEFO selection: the ACTIVE, not-yet-expired lots of an ingredient at a
     * store, earliest expiry first (undated lots last), then oldest received.
     * Locked {@code FOR UPDATE} so a consumption draws each lot exactly once.
     */
    @Query(value = """
            select * from inventory.inventory_batches
            where store_id = cast(:store as uuid)
              and ingredient_id = cast(:ingredient as uuid)
              and status = 'ACTIVE'
              and quantity_base > 0
              and (expiry_date is null or expiry_date > current_date)
            order by expiry_date asc nulls last, received_at asc, batch_id
            for update
            """, nativeQuery = true)
    List<InventoryBatch> pickFefoForUpdate(@Param("store") UUID store, @Param("ingredient") UUID ingredient);

    /** Existing lot row for a receipt merge, locked via the native ``for update``. */
    @Query(value = """
            select * from inventory.inventory_batches
            where store_id = cast(:store as uuid)
              and ingredient_id = cast(:ingredient as uuid)
              and lot_number = :lot
            for update
            """, nativeQuery = true)
    Optional<InventoryBatch> findByLotForUpdate(@Param("store") UUID store, @Param("ingredient") UUID ingredient,
            @Param("lot") String lotNumber);

    /** Write-off/expire target locked before it is retired. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select b from InventoryBatch b where b.id = :id")
    Optional<InventoryBatch> findByIdForUpdate(@Param("id") UUID id);

    /**
     * Valued stock-in merge: adds quantity to an existing lot row (re-activating
     * an EXHAUSTED lot of the same number) and restates its cost to the latest
     * received cost. Only ever increases, so the DB CHECK floor is safe.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update inventory.inventory_batches
            set quantity_base = quantity_base + :q, unit_cost = :c, status = 'ACTIVE',
                updated_at = current_timestamp, version = version + 1
            where batch_id = cast(:id as uuid)
            """, nativeQuery = true)
    int increment(@Param("id") UUID batchId, @Param("q") BigDecimal quantity, @Param("c") BigDecimal unitCost);

    /**
     * Guarded FEFO draw: subtracts {@code :q} only when the lot is still ACTIVE
     * and holds enough. Flips the lot to EXHAUSTED as it reaches zero, back to
     * ACTIVE otherwise (the CASE reads the pre-update quantity). 0 rows means a
     * concurrent draw won the race.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update inventory.inventory_batches
            set quantity_base = quantity_base - :q,
                status = case when quantity_base - :q <= 0 then 'EXHAUSTED' else 'ACTIVE' end,
                updated_at = current_timestamp, version = version + 1
            where batch_id = cast(:id as uuid)
              and status = 'ACTIVE'
              and quantity_base >= :q
            """, nativeQuery = true)
    int decrementIfEnough(@Param("id") UUID batchId, @Param("q") BigDecimal quantity);

    /**
     * Retires a lot in one place: zeroes its remaining quantity and stamps the
     * terminal status (EXPIRED / ADJUSTED_OUT). Guarded on the lot still being
     * ACTIVE so a double write-off updates 0 rows. The caller has already read
     * the remaining quantity under the row lock to build the compensating
     * aggregate movement.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            update inventory.inventory_batches
            set quantity_base = 0, status = :status, updated_at = current_timestamp, version = version + 1
            where batch_id = cast(:id as uuid)
              and status = 'ACTIVE'
            """, nativeQuery = true)
    int retire(@Param("id") UUID batchId, @Param("status") String status);
}
