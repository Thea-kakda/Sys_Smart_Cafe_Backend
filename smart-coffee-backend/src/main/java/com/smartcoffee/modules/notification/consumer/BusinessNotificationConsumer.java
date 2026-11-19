package com.smartcoffee.modules.notification.consumer;

import com.smartcoffee.infrastructure.messaging.inbox.ConsumerInboxService;
import com.smartcoffee.modules.notification.application.NotificationCreationService;
import com.smartcoffee.modules.notification.domain.DeliveryChannel;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

@Component
public class BusinessNotificationConsumer {

    private final ConsumerInboxService inbox;
    private final NotificationCreationService notifications;
    private final ObjectMapper json;

    public BusinessNotificationConsumer(
            ConsumerInboxService inbox,
            NotificationCreationService notifications,
            ObjectMapper json
    ) {
        this.inbox = inbox;
        this.notifications = notifications;
        this.json = json;
    }

    @RabbitListener(
            queues = "${app.notification.queue:smartcoffee.notification}"
    )
    public void receive(String raw) {
        try {
            JsonNode envelope = json.readTree(raw);

            UUID eventId = UUID.fromString(
                    requiredText(envelope, "eventId")
            );

            String eventType = requiredText(
                    envelope,
                    "eventType"
            );

            int eventVersion = envelope
                    .path("eventVersion")
                    .asInt(1);

            inbox.processOnce(
                    "notification",
                    eventId,
                    eventType,
                    eventVersion,
                    () -> processNotification(
                            envelope,
                            eventId,
                            eventType
                    )
            );
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "Invalid notification event",
                    exception
            );
        }
    }

    private void processNotification(
            JsonNode envelope,
            UUID eventId,
            String eventType
    ) {
        JsonNode payload = envelope.path("payload");

        if (!payload.hasNonNull("userId")) {
            return;
        }

        UUID userId = UUID.fromString(
                requiredText(payload, "userId")
        );

        String actionUrl = optionalText(
                payload,
                "actionUrl"
        );

        String entityType = optionalText(
                payload,
                "entityType"
        );

        UUID relatedEntityId = payload.hasNonNull("entityId")
                ? UUID.fromString(
                        requiredText(payload, "entityId")
                )
                : null;

        notifications.create(
                eventId,
                eventType,
                "ORDER",
                "notification."
                        + eventType.toLowerCase(Locale.ROOT),
                payload.toString(),
                actionUrl,
                entityType,
                relatedEntityId,
                List.of(
                        new NotificationCreationService.Recipient(
                                userId,
                                "km-KH",
                                Set.of(
                                        DeliveryChannel.WEBSOCKET,
                                        DeliveryChannel.PUSH
                                )
                        )
                )
        );
    }

    private String requiredText(
            JsonNode parent,
            String fieldName
    ) {
        JsonNode node = parent.get(fieldName);

        if (node == null || node.isNull()) {
            throw new IllegalArgumentException(
                    "Missing required field: " + fieldName
            );
        }

        String value = node.stringValue();

        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "Invalid required field: " + fieldName
            );
        }

        return value;
    }

    private String optionalText(
            JsonNode parent,
            String fieldName
    ) {
        JsonNode node = parent.get(fieldName);

        if (node == null || node.isNull()) {
            return null;
        }

        return node.stringValue();
    }
}