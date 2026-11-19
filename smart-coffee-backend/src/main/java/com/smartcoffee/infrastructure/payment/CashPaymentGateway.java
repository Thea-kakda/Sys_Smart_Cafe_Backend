package com.smartcoffee.infrastructure.payment;
import java.math.BigDecimal; import java.time.Instant; import java.util.UUID; import org.springframework.stereotype.Component;
@Component public class CashPaymentGateway implements PaymentGateway { public String code(){return "CASH";} public PaymentInitiation initiate(UUID id,BigDecimal amount,String currency){return new PaymentInitiation("CASH-"+id,null,null);} public PaymentVerification verify(String ref){return new PaymentVerification(true,"PAID",Instant.now());} }

