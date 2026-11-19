package com.smartcoffee.modules.payment.api;

import jakarta.validation.constraints.NotNull;
import java.util.UUID;

public record KhqrPaymentRequest(@NotNull UUID orderId) {
}

