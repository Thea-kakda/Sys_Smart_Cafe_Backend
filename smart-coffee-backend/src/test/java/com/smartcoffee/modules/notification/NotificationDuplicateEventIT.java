package com.smartcoffee.modules.notification;

import org.junit.jupiter.api.*;

@Disabled("Enable with RabbitMQ and PostgreSQL Testcontainers")
class NotificationDuplicateEventIT {
    @Test
    void duplicateEventCreatesOneNotificationAndOneRecipient() {
        /* Publish same event twice and assert unique source_event_id/type effect. */}
}

