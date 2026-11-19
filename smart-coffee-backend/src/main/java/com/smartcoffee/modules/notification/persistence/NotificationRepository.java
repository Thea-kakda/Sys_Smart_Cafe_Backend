package com.smartcoffee.modules.notification.persistence;

import com.smartcoffee.modules.notification.domain.Notification;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationRepository extends JpaRepository<Notification, UUID> {
    boolean existsBySourceEventIdAndType(UUID event, String type);
}

