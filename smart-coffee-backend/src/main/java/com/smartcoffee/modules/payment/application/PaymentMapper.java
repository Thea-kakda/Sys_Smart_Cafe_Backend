package com.smartcoffee.modules.payment.application;

import com.smartcoffee.modules.payment.api.PaymentConversionResponse;
import com.smartcoffee.modules.payment.api.PaymentResponse;
import com.smartcoffee.modules.payment.domain.Payment;
import org.springframework.stereotype.Component;

@Component
public class PaymentMapper {
    public PaymentResponse response(Payment p, String method, String qr, boolean replay) {
        return new PaymentResponse(p.id(), p.reference(), p.orderId(), method, p.status().name(), p.currency(),
                p.amount(), p.amountReceived(), p.changeAmount(), p.providerReference(), qr, p.expiresAt(), p.paidAt(),
                p.failureCode(), replay, PaymentConversionResponse.from(p.conversionSnapshot()));
    }
}

