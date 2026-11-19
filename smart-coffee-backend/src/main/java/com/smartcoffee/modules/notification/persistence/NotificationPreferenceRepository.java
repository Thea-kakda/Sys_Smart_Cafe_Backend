package com.smartcoffee.modules.notification.persistence;

import com.smartcoffee.modules.notification.domain.*;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, UUID> {
    Optional<NotificationPreference> findByUserIdAndCategoryAndChannel(UUID user, String category,
            DeliveryChannel channel);
}

