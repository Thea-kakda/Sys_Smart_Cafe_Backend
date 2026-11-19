package com.smartcoffee.modules.notification.api;

import java.time.Instant;
import java.util.UUID;

public record NotificationResponse(UUID notificationRecipientId, UUID notificationId, String type, String category,
        String messageKey, String templateArguments, String actionUrl, String priority, Instant createdAt,
        Instant readAt) {
}

