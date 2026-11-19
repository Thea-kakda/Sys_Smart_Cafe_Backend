package com.smartcoffee.modules.production.persistence;

import com.smartcoffee.modules.production.domain.*;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ProductionTicketRepository extends JpaRepository<ProductionTicket, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = "items")
    @Query("select t from ProductionTicket t where t.id=:id")
    Optional<ProductionTicket> lock(@Param("id") UUID id);

    @EntityGraph(attributePaths = "items")
    List<ProductionTicket> findByStoreIdAndStationIdAndStatusInOrderByPriorityDescQueuedAtAsc(UUID store, UUID station,
            Collection<ProductionStatus> statuses);

    boolean existsByOrderIdAndStatusNot(UUID orderId, ProductionStatus status);

    List<ProductionTicket> findByOrderId(UUID orderId);
}

