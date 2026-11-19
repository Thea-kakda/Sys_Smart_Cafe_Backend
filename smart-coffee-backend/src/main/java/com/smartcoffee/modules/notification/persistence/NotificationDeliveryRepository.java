package com.smartcoffee.modules.notification.persistence;

import com.smartcoffee.modules.notification.domain.*;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.*;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, UUID> {
    @Query("select d.id from NotificationDelivery d where d.status='PENDING' and (d.nextAttemptAt is null or d.nextAttemptAt<=:now) order by d.createdAt")
    List<UUID> pending(@Param("now") Instant now, Pageable page);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @EntityGraph(attributePaths = { "recipient", "recipient.notification" })
    @Query("select d from NotificationDelivery d where d.id=:id")
    Optional<NotificationDelivery> lock(@Param("id") UUID id);
}

