package com.smartcoffee.modules.payment.consumer;

import com.smartcoffee.infrastructure.messaging.inbox.ConsumerInboxService;
import com.smartcoffee.modules.payment.application.ReceiptProcessor;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Durable consumption of the settlement-emitted {@code ReceiptRequested} event
 * (corrective receipt slice). Only the authoritative settlement transaction
 * produces this event; a client-side payment confirmation can never issue a
 * receipt. Delivery is deduplicated per event through the shared consumer inbox,
 * and {@link ReceiptProcessor} additionally serializes concurrent issuers on the
 * receipt row, so broker redelivery cannot create a second receipt or object.
 *
 * <p>Failures inside the processor are persisted as FAILED (bounded safe code)
 * and acknowledged; the recovery worker retries them with backoff. A request
 * without {@code receiptId} is ignored here — the recovery worker sweeps the
 * persisted PENDING row instead of guessing an identity.
 */
@Component
public class ReceiptIssuanceConsumer {

    private static final Logger log = LoggerFactory.getLogger(ReceiptIssuanceConsumer.class);

    static final String EVENT_TYPE = "ReceiptRequested";
    static final String CONSUMER = "receipt-issuance";

    private final ConsumerInboxService inbox;
    private final ReceiptProcessor processor;
    private final ObjectMapper json;

    public ReceiptIssuanceConsumer(ConsumerInboxService inbox, ReceiptProcessor processor, ObjectMapper json) {
        this.inbox = inbox;
        this.processor = processor;
        this.json = json;
    }

    @RabbitListener(queues = "${app.receipt.queue:smartcoffee.receipts}")
    public void receive(String raw) {
        try {
            JsonNode envelope = json.readTree(raw);

            UUID eventId = UUID.fromString(requiredText(envelope, "eventId"));
            String eventType = requiredText(envelope, "eventType");
            int eventVersion = envelope.path("eventVersion").asInt(1);

            if (!EVENT_TYPE.equals(eventType)) {
                log.info("Ignoring unsupported receipt event type '{}' for event {}", eventType, eventId);
                return;
            }

            JsonNode payload = envelope.path("payload");
            if (!payload.hasNonNull("receiptId")) {
                log.info("Ignoring receipt request without receiptId; recovery will sweep it eventId={}", eventId);
                return;
            }

            UUID receiptId = UUID.fromString(requiredText(payload, "receiptId"));

            inbox.processOnce(CONSUMER, eventId, eventType, eventVersion, () -> {
                ReceiptProcessor.Outcome outcome = processor.process(receiptId);
                if (outcome == ReceiptProcessor.Outcome.FAILED) {
                    log.warn("Receipt issuance recorded FAILED receiptId={}", receiptId);
                } else if (outcome == ReceiptProcessor.Outcome.MISSING) {
                    log.warn("Receipt request references an unknown receipt receiptId={}", receiptId);
                }
            });
        } catch (Exception exception) {
            throw new IllegalArgumentException("Invalid receipt event", exception);
        }
    }

    private String requiredText(JsonNode parent, String fieldName) {
        JsonNode node = parent.get(fieldName);
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException("Missing required field: " + fieldName);
        }
        String value = node.stringValue();
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Invalid required field: " + fieldName);
        }
        return value;
    }
}
