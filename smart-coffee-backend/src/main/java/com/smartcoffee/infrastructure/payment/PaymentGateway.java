package com.smartcoffee.infrastructure.payment;
import java.math.BigDecimal; import java.time.Instant; import java.util.UUID;
public interface PaymentGateway { String code(); PaymentInitiation initiate(UUID paymentId,BigDecimal amount,String currency); PaymentVerification verify(String providerReference); record PaymentInitiation(String providerReference,String qrPayload,Instant expiresAt){} record PaymentVerification(boolean paid,String providerStatus,Instant verifiedAt){} }

