package com.smartcoffee.infrastructure.payment;
import java.math.BigDecimal; import java.time.*; import java.util.UUID; import org.springframework.context.annotation.Profile; import org.springframework.stereotype.Component;
@Component @Profile({"local","test"}) public class MockKhqrPaymentGateway implements PaymentGateway { public String code(){return "KHQR";} public PaymentInitiation initiate(UUID id,BigDecimal amount,String currency){return new PaymentInitiation("MOCK-"+id,"mock-khqr-payload",Instant.now().plusSeconds(300));} public PaymentVerification verify(String ref){return new PaymentVerification(true,"PAID",Instant.now());} }

