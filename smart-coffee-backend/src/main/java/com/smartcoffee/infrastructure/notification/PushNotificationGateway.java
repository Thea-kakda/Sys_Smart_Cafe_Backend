package com.smartcoffee.infrastructure.notification;
public interface PushNotificationGateway { DeliveryResult send(String protectedToken,String title,String body); record DeliveryResult(boolean delivered,String providerReference,String errorCode){} }

