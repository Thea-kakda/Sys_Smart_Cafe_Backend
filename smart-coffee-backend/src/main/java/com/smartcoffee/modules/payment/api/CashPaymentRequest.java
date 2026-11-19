package com.smartcoffee.modules.payment.api;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.util.UUID;

/**
 * Cash payment request (Checkpoint 5C). {@code amountReceived} is the physical
 * cash handed over in {@code tenderCurrency}. The backend derives the
 * settlement value in the order's currency from the authoritative active rate;
 * the client never dictates the rate.
 *
 * <p>{@code exchangeRate} is DEPRECATED and retained for one compatibility
 * release only as an expected preview value. It is NOT used to compute
 * settlement — if it disagrees with the backend-selected rate beyond tolerance
 * the payment is rejected with {@code RATE_STALE}. Clients should stop sending
 * it and use the conversion preview endpoint to show the customer a quote.
 */
public record CashPaymentRequest(@NotNull UUID orderId, @NotNull UUID storeId,
        @NotNull @DecimalMin("0.0001") BigDecimal amountReceived, @NotBlank String tenderCurrency,
        @Deprecated @DecimalMin("0.00000001") BigDecimal exchangeRate) {
}
