package com.smartcoffee.infrastructure.messaging.outbox;

import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;

// import org.springframework.data.repository.query.Param;
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, UUID> {
    @Query("select e from OutboxEvent e where e.status='PENDING' and e.availableAt <= CURRENT_TIMESTAMP order by e.occurredAt")
    List<OutboxEvent> findPublishable(Pageable pageable);
}

