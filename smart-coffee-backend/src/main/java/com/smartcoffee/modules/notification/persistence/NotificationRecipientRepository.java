package com.smartcoffee.modules.notification.persistence;

import com.smartcoffee.modules.notification.domain.NotificationRecipient;
import java.util.*;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface NotificationRecipientRepository extends JpaRepository<NotificationRecipient, UUID> {
    @EntityGraph(attributePaths = "notification")
    Page<NotificationRecipient> findByUserIdAndArchivedAtIsNullOrderByCreatedAtDesc(UUID user, Pageable pageable);

    @Query("select count(r) from NotificationRecipient r where r.userId=:user and r.readAt is null and r.archivedAt is null")
    long unread(@Param("user") UUID user);

    @Modifying
    @Query("update NotificationRecipient r set r.readAt=CURRENT_TIMESTAMP where r.userId=:user and r.readAt is null")
    int markAllRead(@Param("user") UUID user);

    Optional<NotificationRecipient> findByIdAndUserId(UUID id, UUID user);
}

