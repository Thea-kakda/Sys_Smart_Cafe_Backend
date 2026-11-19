package com.smartcoffee.config;

import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.ExchangeBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqTopologyConfig {

    public static final String BUSINESS_EXCHANGE =
            "smartcoffee.business.events";

    public static final String DEAD_EXCHANGE =
            "smartcoffee.deadletter";

    public static final String NOTIFICATION_QUEUE =
            "smartcoffee.notification";

    public static final String PRODUCTION_QUEUE =
            "smartcoffee.production";

    public static final String RECEIPT_QUEUE =
            "smartcoffee.receipts";

    @Bean
    TopicExchange businessExchange() {
        return ExchangeBuilder
                .topicExchange(BUSINESS_EXCHANGE)
                .durable(true)
                .build();
    }

    @Bean
    DirectExchange deadExchange() {
        return ExchangeBuilder
                .directExchange(DEAD_EXCHANGE)
                .durable(true)
                .build();
    }

    @Bean
    Queue notificationQueue() {
        return QueueBuilder
                .durable(NOTIFICATION_QUEUE)
                .deadLetterExchange(DEAD_EXCHANGE)
                .deadLetterRoutingKey("notification.dead")
                .build();
    }

    @Bean
    Queue notificationDeadQueue() {
        return QueueBuilder
                .durable("smartcoffee.notification.dead")
                .build();
    }

    @Bean
    Queue productionQueue() {
        return QueueBuilder
                .durable(PRODUCTION_QUEUE)
                .deadLetterExchange(DEAD_EXCHANGE)
                .deadLetterRoutingKey("production.dead")
                .build();
    }

    @Bean
    Queue productionDeadQueue() {
        return QueueBuilder
                .durable("smartcoffee.production.dead")
                .build();
    }

    @Bean
    Queue receiptQueue() {
        return QueueBuilder
                .durable(RECEIPT_QUEUE)
                .deadLetterExchange(DEAD_EXCHANGE)
                .deadLetterRoutingKey("receipt.dead")
                .build();
    }

    @Bean
    Queue receiptDeadQueue() {
        return QueueBuilder
                .durable("smartcoffee.receipts.dead")
                .build();
    }

    @Bean
    Declarables notificationBindings(
            TopicExchange businessExchange,
            DirectExchange deadExchange,
            Queue notificationQueue,
            Queue notificationDeadQueue) {

        return new Declarables(
                BindingBuilder
                        .bind(notificationQueue)
                        .to(businessExchange)
                        .with("order.ready.v1"),

                BindingBuilder
                        .bind(notificationQueue)
                        .to(businessExchange)
                        .with("payment.*.v1"),

                BindingBuilder
                        .bind(notificationQueue)
                        .to(businessExchange)
                        .with("loyalty.#"),

                BindingBuilder
                        .bind(notificationDeadQueue)
                        .to(deadExchange)
                        .with("notification.dead")
        );
    }

    @Bean
    Declarables productionBindings(
            TopicExchange businessExchange,
            DirectExchange deadExchange,
            Queue productionQueue,
            Queue productionDeadQueue) {

        return new Declarables(
                // A confirmed payment is the authoritative trigger that an
                // order has entered production; the production consumer
                // creates station tickets from it.
                BindingBuilder
                        .bind(productionQueue)
                        .to(businessExchange)
                        .with("payment.completed.v1"),

                // A cancelled order must retire its queued production work; the
                // production consumer treats this as the cancellation trigger.
                BindingBuilder
                        .bind(productionQueue)
                        .to(businessExchange)
                        .with("order.cancelled.v1"),

                BindingBuilder
                        .bind(productionDeadQueue)
                        .to(deadExchange)
                        .with("production.dead")
        );
    }

    @Bean
    Declarables receiptBindings(
            TopicExchange businessExchange,
            DirectExchange deadExchange,
            Queue receiptQueue,
            Queue receiptDeadQueue) {

        return new Declarables(
                // The settlement transaction's durable receipt request is the
                // only trigger that may issue a customer document.
                BindingBuilder
                        .bind(receiptQueue)
                        .to(businessExchange)
                        .with("receipt.requested.v1"),

                BindingBuilder
                        .bind(receiptDeadQueue)
                        .to(deadExchange)
                        .with("receipt.dead")
        );
    }
}
