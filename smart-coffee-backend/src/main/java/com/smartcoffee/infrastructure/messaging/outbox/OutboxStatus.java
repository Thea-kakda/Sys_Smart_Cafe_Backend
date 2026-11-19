package com.smartcoffee.infrastructure.messaging.outbox;

public enum OutboxStatus {
    PENDING, PROCESSING, PUBLISHED, FAILED
}

