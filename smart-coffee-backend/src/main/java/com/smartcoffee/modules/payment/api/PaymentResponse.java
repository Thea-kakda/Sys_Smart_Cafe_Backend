package com.smartcoffee.modules.payment.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(UUID paymentId, String paymentReference, UUID orderId, String method, String status,
        String currencyCode, BigDecimal amount, BigDecimal amountReceived, BigDecimal changeAmount,
        String providerReference, String qrPayload, Instant expiresAt, Instant paidAt, String failureCode,
        boolean idempotentReplay, PaymentConversionResponse conversion) {
}

