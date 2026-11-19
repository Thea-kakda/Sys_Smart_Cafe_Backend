package com.smartcoffee.infrastructure.notification;
import org.springframework.context.annotation.Profile; import org.springframework.stereotype.Component;
@Component @Profile({"local","test"}) public class MockPushGateway implements PushNotificationGateway { public DeliveryResult send(String t,String title,String body){return new DeliveryResult(true,"mock-push",null);} }

