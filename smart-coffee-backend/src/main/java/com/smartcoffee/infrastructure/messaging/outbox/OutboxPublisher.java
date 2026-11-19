package com.smartcoffee.infrastructure.messaging.outbox;

import com.smartcoffee.config.RabbitMqConfig;
import java.util.List;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class OutboxPublisher {
    private final OutboxEventRepository repo;
    private final RabbitTemplate rabbit;

    public OutboxPublisher(OutboxEventRepository repo, RabbitTemplate rabbit) {
        this.repo = repo;
        this.rabbit = rabbit;
    }

    @Scheduled(fixedDelayString = "${app.outbox.poll-delay-ms:1000}")
    @Transactional
    public void publish() {
        List<OutboxEvent> events = repo.findPublishable(PageRequest.of(0, 50));
        for (var e : events) {
            try {
                rabbit.convertAndSend(RabbitMqConfig.EXCHANGE, e.routingKey(), e.payload());
                e.published();
            } catch (RuntimeException ex) {
                e.retry();
            }
        }
    }
}

