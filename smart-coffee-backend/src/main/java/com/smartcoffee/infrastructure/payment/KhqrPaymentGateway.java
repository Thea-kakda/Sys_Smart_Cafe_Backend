package com.smartcoffee.infrastructure.payment;
import java.math.BigDecimal; 
// import java.time.Instant; 
import java.util.UUID; 
import org.springframework.context.annotation.Profile; 
import org.springframework.stereotype.Component;
@Component @Profile("prod") public class KhqrPaymentGateway implements PaymentGateway { public String code(){return "KHQR";} public PaymentInitiation initiate(UUID id,BigDecimal amount,String currency){throw new UnsupportedOperationException("Configure real KHQR provider adapter");} public PaymentVerification verify(String ref){throw new UnsupportedOperationException("Configure real KHQR provider adapter");} }

