package com.smartcoffee.modules.inventory.persistence;

import com.smartcoffee.modules.inventory.domain.StockTransaction;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockTransactionRepository extends JpaRepository<StockTransaction, UUID> {
    /** True when a ledger row of {@code type} already names this reservation. */
    boolean existsByReservationIdAndType(UUID reservationId, com.smartcoffee.modules.inventory.domain.StockTransactionType type);

    /** How many ledger rows of {@code type} name this reservation (duplicate detection). */
    long countByReservationIdAndType(UUID reservationId, com.smartcoffee.modules.inventory.domain.StockTransactionType type);

    /** True when a ledger row of {@code type} already references this aggregate id (Slice 5A production uniqueness). */
    boolean existsByReferenceIdAndType(UUID referenceId, com.smartcoffee.modules.inventory.domain.StockTransactionType type);
}

