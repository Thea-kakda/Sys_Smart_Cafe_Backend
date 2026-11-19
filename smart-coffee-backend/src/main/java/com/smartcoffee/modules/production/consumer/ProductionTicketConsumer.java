package com.smartcoffee.modules.production.consumer;

import com.smartcoffee.infrastructure.messaging.EventTypes;
import com.smartcoffee.infrastructure.messaging.inbox.ConsumerInboxService;
import com.smartcoffee.modules.production.application.ProductionTicketService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/**
 * Keeps production station tickets in step with the order lifecycle.
 *
 * <p>Two authoritative business events drive this consumer:</p>
 * <ul>
 *   <li>{@code payment.completed.v1} ({@link EventTypes#PAYMENT_COMPLETED}) —
 *       turns a confirmed order into one ticket per routing station so the
 *       KDS/BDS queues receive work;</li>
 *   <li>{@code order.cancelled.v1} ({@link EventTypes#ORDER_CANCELLED}) — retires
 *       any tickets already queued for a cancelled order so the boards stop
 *       showing work that will never be served.</li>
 * </ul>
 *
 * <p>Any other event type is explicitly ignored without touching production
 * data (never defaulted into creation). Processing of the two supported events
 * is idempotent per event via the shared consumer inbox, so broker redelivery or
 * duplicate outbox publication cannot create duplicate tickets or double-cancel.</p>
 */
@Component
public class ProductionTicketConsumer {

    private static final Logger log = LoggerFactory.getLogger(ProductionTicketConsumer.class);

    private final ConsumerInboxService inbox;
    private final ProductionTicketService tickets;
    private final ObjectMapper json;

    public ProductionTicketConsumer(
            ConsumerInboxService inbox,
            ProductionTicketService tickets,
            ObjectMapper json
    ) {
        this.inbox = inbox;
        this.tickets = tickets;
        this.json = json;
    }

    @RabbitListener(
            queues = "${app.production.queue:smartcoffee.production}"
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

            JsonNode payload = envelope.path("payload");

            if (!payload.hasNonNull("orderId")) {
                // Not an order-bearing event; acknowledge without work.
                return;
            }

            UUID orderId = UUID.fromString(
                    requiredText(payload, "orderId")
            );

            // Explicit, safe dispatch: only the two events this consumer owns
            // may mutate production state. An unknown type is acknowledged and
            // ignored (never silently routed into ticket creation).
            if (EventTypes.PAYMENT_COMPLETED.equals(eventType)) {
                inbox.processOnce(
                        "production",
                        eventId,
                        eventType,
                        eventVersion,
                        () -> tickets.createForOrder(orderId)
                );
            } else if (EventTypes.ORDER_CANCELLED.equals(eventType)) {
                inbox.processOnce(
                        "production",
                        eventId,
                        eventType,
                        eventVersion,
                        () -> tickets.cancelForOrder(orderId)
                );
            } else {
                // Not actionable for production: log safe metadata only and let
                // the message be acknowledged. The queue binding already limits
                // traffic to the two events above; this guards against a future
                // mis-bind without risking incorrect ticket creation.
                log.info("Ignoring unsupported production event type '{}' for event {}", eventType, eventId);
            }
        } catch (Exception exception) {
            throw new IllegalArgumentException(
                    "Invalid production event",
                    exception
            );
        }
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
}
