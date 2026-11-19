package com.smartcoffee.modules.inventory.persistence;

import com.smartcoffee.modules.inventory.domain.PurchaseOrder;
import com.smartcoffee.modules.inventory.domain.PurchaseOrderStatus;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface PurchaseOrderRepository extends JpaRepository<PurchaseOrder, UUID> {

    /**
     * Locks the header for the receive/submit/cancel mutation paths. The lines
     * are read lazily inside the same transaction after the lock is held, so no
     * concurrent receipt can interleave between the over-receipt guard and the
     * balance write.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PurchaseOrder p where p.id = :id")
    Optional<PurchaseOrder> findByIdForUpdate(@Param("id") UUID id);

    @Query("select p from PurchaseOrder p left join fetch p.lines where p.id = :id")
    Optional<PurchaseOrder> findWithLines(@Param("id") UUID id);

    @Query("select p from PurchaseOrder p "
            + "where p.storeId = :store and (:statuses = false or p.status in :statusList) "
            + "order by p.orderedAt desc, p.id")
    Page<PurchaseOrder> byStore(@Param("store") UUID store, @Param("statuses") boolean filterStatus,
            @Param("statusList") List<PurchaseOrderStatus> statusList, Pageable pageable);
}
