package com.smartcoffee.modules.inventory.persistence;

import com.smartcoffee.modules.inventory.domain.StockTransfer;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;

public interface StockTransferRepository extends JpaRepository<StockTransfer, UUID> {

    /**
     * Locks the header for the dispatch/receive/cancel mutation paths so the
     * status guard and the paired balance writes cannot interleave with a
     * concurrent action on the same transfer.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from StockTransfer t where t.id = :id")
    Optional<StockTransfer> findByIdForUpdate(@Param("id") UUID id);

    @Query("select t from StockTransfer t left join fetch t.lines where t.id = :id")
    Optional<StockTransfer> findWithLines(@Param("id") UUID id);

    /**
     * Transfers touching a store either as source or destination (a branch sees
     * both what it shipped out and what it received), newest-first.
     */
    @Query("select t from StockTransfer t "
            + "where t.sourceStoreId = :store or t.destStoreId = :store "
            + "order by t.createdAt desc, t.id")
    Page<StockTransfer> byStore(@Param("store") UUID store, Pageable pageable);
}
