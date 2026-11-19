package com.smartcoffee.modules.payment.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Read-only currency conversion preview request (Checkpoint 5B). The client
 * supplies only intent — which branch, which currencies and how much. The
 * selected rate, direction, rounding and result are decided authoritatively by
 * the backend; nothing here is trusted as a settlement value.
 */
public record CurrencyConversionPreviewRequest(
        @NotNull UUID storeId,
        @NotNull String sourceCurrency,
        @NotNull String targetCurrency,
        @NotNull @DecimalMin("0.00") BigDecimal amount) {
}
