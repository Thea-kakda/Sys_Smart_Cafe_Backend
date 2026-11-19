package com.smartcoffee.infrastructure.notification;
import org.springframework.context.annotation.Profile; import org.springframework.stereotype.Component;
@Component @Profile({"local","test"}) public class MockEmailGateway implements EmailGateway { public DeliveryResult send(String r,String s,String h){return new DeliveryResult(true,"mock-email",null);} }

