package com.smartcoffee.modules.production.persistence;

import com.smartcoffee.modules.production.domain.ProductionTicketItem;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface ProductionTicketItemRepository extends JpaRepository<ProductionTicketItem, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select i from ProductionTicketItem i join fetch i.ticket where i.id=:id")
    Optional<ProductionTicketItem> lock(@Param("id") UUID id);

    @Query("select i.ticket.id from ProductionTicketItem i where i.id=:id")
    Optional<UUID> ticketId(@Param("id") UUID id);
}

