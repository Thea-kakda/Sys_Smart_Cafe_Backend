package com.smartcoffee.modules.payment.api;

import java.util.UUID;

public record PaymentMethodResponse(UUID paymentMethodId, String code, String name, String type) {
}

