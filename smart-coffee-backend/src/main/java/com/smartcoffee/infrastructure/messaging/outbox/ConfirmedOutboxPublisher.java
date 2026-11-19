package com.smartcoffee.infrastructure.messaging.outbox;

import com.smartcoffee.config.RabbitMqTopologyConfig;
import java.time.*;
import java.util.UUID;
import org.slf4j.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ConfirmedOutboxPublisher {
    private static final Logger log = LoggerFactory.getLogger(ConfirmedOutboxPublisher.class);
    private final OutboxClaimRepository claims;
    private final RabbitTemplate rabbit;
    private final String owner = UUID.randomUUID().toString();
    private final int batch;

    public ConfirmedOutboxPublisher(OutboxClaimRepository claims, RabbitTemplate rabbit,
            @Value("${app.outbox.batch-size:50}") int batch) {
        this.claims = claims;
        this.rabbit = rabbit;
        this.batch = batch;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-delay-ms:1000}")
    public void publish() {
        for (var e : claims.claim(owner, batch, Instant.now().minusSeconds(120))) {
            try {
                CorrelationData cd = new CorrelationData(e.id().toString());
                rabbit.convertAndSend(RabbitMqTopologyConfig.BUSINESS_EXCHANGE, e.routingKey(), e.payload(), cd);
                var confirm = cd.getFuture().get(Duration.ofSeconds(5).toMillis(),
                        java.util.concurrent.TimeUnit.MILLISECONDS);
                if (confirm.ack()) {
                    claims.markPublished(e.id());
                } else {
                    log.warn(
                            "outbox publish negatively acknowledged id={} reason={}",
                            e.id(),
                            confirm.reason());

                    claims.markRetry(e.id(), "BROKER_NACK");
                }
            } catch (Exception ex) {
                log.warn(
                        "Outbox publish failed id={}",
                        e.id(),
                        ex);

                claims.markRetry(
                        e.id(),
                        "PUBLISH_FAILED");
            }
        }
    }
}

