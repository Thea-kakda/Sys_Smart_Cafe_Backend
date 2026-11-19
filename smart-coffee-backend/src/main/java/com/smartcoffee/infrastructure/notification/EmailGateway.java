package com.smartcoffee.infrastructure.notification;

public interface EmailGateway {
    DeliveryResult send(String recipient, String subject, String html);

    record DeliveryResult(boolean delivered, String providerReference, String errorCode) {
    }
}
