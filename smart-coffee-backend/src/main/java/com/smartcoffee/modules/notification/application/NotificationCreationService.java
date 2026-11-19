package com.smartcoffee.modules.notification.application;

import com.smartcoffee.modules.notification.domain.*;
import com.smartcoffee.modules.notification.persistence.NotificationRepository;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class NotificationCreationService {
    public record Recipient(UUID userId, String locale, Set<DeliveryChannel> channels) {
    }

    private final NotificationRepository notifications;

    public NotificationCreationService(NotificationRepository n) {
        notifications = n;
    }

    @Transactional
    public UUID create(UUID eventId, String type, String category, String key, String args, String action,
            String entityType, UUID entityId, List<Recipient> recipients) {
        if (notifications.existsBySourceEventIdAndType(eventId, type))
            return null;
        var n = Notification.create(eventId, type, category, key, args, action, entityType, entityId, "NORMAL",
                Instant.now().plusSeconds(90L * 24 * 3600));
        for (var x : recipients) {
            var r = new NotificationRecipient(x.userId(), x.locale());
            r.addDelivery(new NotificationDelivery(DeliveryChannel.IN_APP));
            x.channels().stream().filter(c -> c != DeliveryChannel.IN_APP)
                    .forEach(c -> r.addDelivery(new NotificationDelivery(c)));
            n.addRecipient(r);
        }
        return notifications.save(n).id();
    }
}

